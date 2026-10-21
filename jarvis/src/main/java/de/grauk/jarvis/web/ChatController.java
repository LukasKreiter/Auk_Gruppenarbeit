package de.grauk.jarvis.web;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.grauk.jarvis.assistant.AssistantEvent;
import de.grauk.jarvis.assistant.AssistantService;
import de.grauk.jarvis.conversation.ConversationService;
import de.grauk.jarvis.conversation.ConversationService.MessageView;
import de.grauk.jarvis.status.MetricsService;
import de.grauk.jarvis.web.Dtos.ChatRequest;
import de.grauk.jarvis.web.Dtos.ConversationDto;
import de.grauk.jarvis.web.Dtos.CreateConversationRequest;
import de.grauk.jarvis.web.Dtos.MessageDto;
import de.grauk.jarvis.web.Dtos.RenameRequest;
import de.grauk.jarvis.web.SsePayloads.DonePayload;
import de.grauk.jarvis.web.SsePayloads.ErrorPayload;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON-API für Konversationen und Nachrichten sowie der Chat-Stream.
 * Der Fachablauf liegt in {@link AssistantService} (KI-Kern) und {@link ConversationService} (Persistenz);
 * dieser Controller übersetzt nur zwischen HTTP/SSE und beiden.
 */
@RestController
@RequestMapping("/api/conversations")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** Abstand der SSE-Kommentare ({@code :keepalive}) während eines Streams. */
    static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(15);

    /** Zusatzzeit für den Container-Timeout des Emitters, damit der eigene Timeout zuerst auslöst. */
    private static final Duration CONTAINER_TIMEOUT_GRACE = Duration.ofSeconds(30);

    private final AssistantService assistant;
    private final ConversationService conversations;
    private final JsonMapper json;
    private final JarvisWebProperties properties;
    private final MetricsService metrics;

    /** Konversationen mit laufendem Stream (höchstens einer je Konversation). */
    private final Set<Long> activeStreams = ConcurrentHashMap.newKeySet();

    /** Gemeinsamer Scheduler für alle Heartbeats (kein Thread pro Stream). */
    private final ScheduledThreadPoolExecutor heartbeats = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "chat-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /** Nur für Tests veränderbar. */
    Duration heartbeatInterval = DEFAULT_HEARTBEAT;

    public ChatController(AssistantService assistant, ConversationService conversations, JsonMapper json,
                          JarvisWebProperties properties, MetricsService metrics) {
        this.metrics = metrics;
        this.assistant = assistant;
        this.conversations = conversations;
        this.json = json;
        this.properties = properties;
        this.heartbeats.setRemoveOnCancelPolicy(true);
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
    }

    @GetMapping
    public List<ConversationDto> list() {
        return conversations.list().stream().map(ConversationDto::from).toList();
    }

    @PostMapping
    public ResponseEntity<ConversationDto> create(@Valid @RequestBody(required = false) CreateConversationRequest request) {
        ConversationDto created = ConversationDto.from(conversations.create(request == null ? null : request.title()));
        return ResponseEntity.created(URI.create("/api/conversations/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ConversationDto get(@PathVariable long id) {
        return ConversationDto.from(conversations.get(id));
    }

    @PutMapping("/{id}")
    public ConversationDto rename(@PathVariable long id, @Valid @RequestBody RenameRequest request) {
        return ConversationDto.from(conversations.rename(id, request.title()));
    }

    /** @throws StreamActiveException (409), solange für die Konversation eine Antwort gestreamt wird */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        if (activeStreams.contains(id)) {
            throw new StreamActiveException("Antwort läuft noch");
        }
        conversations.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> messages(@PathVariable long id) {
        return conversations.messages(id).stream().map(MessageDto::from).toList();
    }

    /**
     * Speichert die Nutzernachricht und streamt die Antwort als Server-Sent Events. Eventnamen und Nutzdaten
     * siehe {@link SsePayloads}; der Stream endet nach genau einem {@code done} oder {@code error}.
     * Bricht der Client ab, wird die Anfrage an das Modell abgebrochen; die Nutzernachricht bleibt gespeichert,
     * eine unvollständige Antwort nicht. Pro Konversation läuft höchstens ein Stream.
     *
     * @throws de.grauk.jarvis.conversation.ConversationNotFoundException (404) vor Beginn des Streams
     * @throws StreamActiveException (409), wenn für die Konversation bereits ein Stream läuft
     */
    @PostMapping("/{id}/messages")
    public SseEmitter send(@PathVariable long id, @Valid @RequestBody ChatRequest request) {
        String text = request.text().strip();
        if (!activeStreams.add(id)) {
            throw new StreamActiveException("Antwort läuft noch");
        }
        Disposable.Composite subscription = Disposables.composite();
        // zuerst eintragen: jedes Ende (done, error, Timeout, Abbruch) räumt den Eintrag genau einmal auf
        subscription.add(() -> activeStreams.remove(id));
        try {
            conversations.saveUserMessage(id, text, request.language(), request.sttMs());
            if (request.sttMs() != null) {
                metrics.recordStt(request.sttMs());
            }

            // Spring markiert den Emitter vor dem onTimeout-Callback als beendet: dort ist kein send() mehr möglich.
            // Das Timeout wird daher selbst überwacht (sendet das error-Event); der Container-Timeout greift erst danach.
            Duration timeout = properties.sseTimeout();
            SseEmitter emitter = new SseEmitter(timeout.plus(CONTAINER_TIMEOUT_GRACE).toMillis());
            Stream stream = new Stream(id, emitter, subscription);

            emitter.onCompletion(subscription::dispose);
            Runnable onTimeout = () -> {
                log.warn("Chat-Stream für Konversation {} nach {} abgebrochen (Timeout)", id, timeout);
                terminate(stream, "error",
                        new ErrorPayload("Zeitüberschreitung – die Antwort hat zu lange gedauert"));
            };
            emitter.onTimeout(onTimeout); // Fallback; terminate() läuft höchstens einmal
            ScheduledFuture<?> timeoutTask = heartbeats.schedule(onTimeout, Math.max(1, timeout.toMillis()),
                    TimeUnit.MILLISECONDS);
            subscription.add(() -> timeoutTask.cancel(false));
            emitter.onError(e -> {
                log.debug("Chat-Stream für Konversation {} vom Client beendet: {}", id, e.toString());
                stream.finished.set(true);
                subscription.dispose();
            });

            long periodMs = Math.max(1, heartbeatInterval.toMillis());
            ScheduledFuture<?> heartbeat = heartbeats.scheduleWithFixedDelay(
                    () -> heartbeat(stream), periodMs, periodMs, TimeUnit.MILLISECONDS);
            subscription.add(() -> heartbeat.cancel(false));

            // publishOn: Persistieren (JPA, blockierend) darf nicht auf einem Reactor-Netty-/Event-Thread laufen
            subscription.add(assistant.stream(id, text)
                    .publishOn(Schedulers.boundedElastic())
                    .subscribe(
                            event -> handle(stream, event),
                            error -> {
                                // der AssistantService beendet den Flux normalerweise nie mit onError
                                log.error("Unerwarteter Fehler im Chat-Stream (Konversation {})", id, error);
                                terminate(stream, "error", new ErrorPayload("Unerwarteter Fehler bei der Anfrage"));
                            },
                            () -> {
                                if (!stream.finished.get()) {
                                    log.warn("Chat-Stream (Konversation {}) endete ohne done/error", id);
                                    terminate(stream, "error", new ErrorPayload("Antwort unvollständig beendet"));
                                }
                            }));
            return emitter;
        } catch (RuntimeException e) {
            subscription.dispose();
            throw e;
        }
    }

    /** Zustand eines laufenden Streams. */
    private static final class Stream {
        final long conversationId;
        final SseEmitter emitter;
        final Disposable.Composite subscription;
        final AtomicBoolean finished = new AtomicBoolean();

        Stream(long conversationId, SseEmitter emitter, Disposable.Composite subscription) {
            this.conversationId = conversationId;
            this.emitter = emitter;
            this.subscription = subscription;
        }
    }

    private void heartbeat(Stream stream) {
        if (stream.finished.get()) {
            return;
        }
        try {
            stream.emitter.send(SseEmitter.event().comment("keepalive"));
        } catch (IOException | IllegalStateException e) {
            clientGone(stream, e);
        } catch (RuntimeException e) {
            log.warn("Heartbeat für Konversation {} fehlgeschlagen", stream.conversationId, e);
        }
    }

    private void handle(Stream stream, AssistantEvent event) {
        if (stream.finished.get()) {
            return;
        }
        long conversationId = stream.conversationId;
        try {
            switch (event) {
                case AssistantEvent.Done done -> {
                    MessageView saved;
                    try {
                        saved = conversations.saveAssistantMessage(conversationId, done);
                    } catch (RuntimeException e) {
                        log.error("Antwort der Konversation {} konnte nicht gespeichert werden", conversationId, e);
                        terminate(stream, "error", new ErrorPayload("Die Antwort konnte nicht gespeichert werden"));
                        return;
                    }
                    try {
                        metrics.recordLlm(done.llmMillis());
                    } catch (RuntimeException e) {
                        log.warn("Latenz der Konversation {} konnte nicht erfasst werden", conversationId, e);
                    }
                    terminate(stream, "done", new DonePayload(saved.message().getId(),
                            done.fullText(), done.provider(), done.model(), done.llmMillis(),
                            MessageDto.from(saved).toolCalls()));
                }
                case AssistantEvent.Error error -> {
                    log.warn("Anfrage in Konversation {} fehlgeschlagen: {}", conversationId, error.userMessage());
                    terminate(stream, error.sseName(), SsePayloads.of(error));
                }
                default -> {
                    String data = json.writeValueAsString(SsePayloads.of(event));
                    try {
                        stream.emitter.send(SseEmitter.event().name(event.sseName()).data(data));
                    } catch (IOException | IllegalStateException e) {
                        clientGone(stream, e);
                    }
                }
            }
        } catch (RuntimeException e) {
            // Programmierfehler (Payload-Bau, Serialisierung, ...): nicht als Client-Abbruch tarnen
            log.error("Fehler beim Verarbeiten eines Events der Konversation {}", conversationId, e);
            terminate(stream, "error", new ErrorPayload("Interner Fehler bei der Verarbeitung der Antwort"));
        }
    }

    /** Client ist weg (Tab geschlossen, Abbruch): Anfrage an das Modell nicht weiterlaufen lassen. */
    private void clientGone(Stream stream, Exception cause) {
        if (stream.finished.getAndSet(true)) {
            return;
        }
        log.info("Client hat Stream abgebrochen, Konversation {}", stream.conversationId);
        log.debug("Ursache des Stream-Abbruchs: {}", cause.toString());
        stream.subscription.dispose();
    }

    /**
     * Sendet das letzte Event und schließt den Stream; höchstens einmal pro Stream (genau ein done oder error).
     * Sendefehler (Client weg) werden nur geloggt, alles andere als Fehler.
     */
    private void terminate(Stream stream, String name, Object payload) {
        if (!stream.finished.compareAndSet(false, true)) {
            return;
        }
        try {
            String data = json.writeValueAsString(payload);
            try {
                stream.emitter.send(SseEmitter.event().name(name).data(data));
                stream.emitter.complete();
            } catch (IOException | IllegalStateException e) {
                log.info("Client hat Stream abgebrochen, Konversation {} (Abschluss-Event '{}' nicht zustellbar)",
                        stream.conversationId, name);
                log.debug("Ursache: {}", e.toString());
            }
        } catch (RuntimeException e) {
            log.error("Abschluss-Event '{}' der Konversation {} konnte nicht gebaut werden",
                    name, stream.conversationId, e);
            try {
                stream.emitter.complete();
            } catch (RuntimeException ignored) {
                // Stream ist ohnehin schon beendet
            }
        } finally {
            stream.subscription.dispose();
        }
    }
}
