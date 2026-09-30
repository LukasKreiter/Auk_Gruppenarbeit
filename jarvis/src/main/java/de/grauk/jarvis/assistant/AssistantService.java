package de.grauk.jarvis.assistant;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.anthropic.errors.AnthropicServiceException;
import de.grauk.jarvis.settings.SettingsService;
import de.grauk.jarvis.settings.SettingsService.SettingsSnapshot;
import de.grauk.jarvis.tools.ToolInvocationListener;
import de.grauk.jarvis.tools.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Einstiegspunkt der Chat-Pipeline; einzige Klasse, die web/ aufruft. Persistiert nichts –
 * das macht conversation/ anhand von {@link AssistantEvent.Done}.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    /** Claude verlangt max_tokens; mit Thinking muss es größer als das Thinking-Budget sein. */
    private static final int ANTHROPIC_MAX_TOKENS = 4096;
    private static final int ANTHROPIC_MAX_TOKENS_THINKING = 8192;
    private static final long ANTHROPIC_THINKING_BUDGET = 2048;

    private final ChatProviderRegistry registry;
    private final SettingsService settings;
    private final ToolRegistry toolRegistry;

    public AssistantService(ChatProviderRegistry registry, SettingsService settings, ToolRegistry toolRegistry) {
        this.registry = registry;
        this.settings = settings;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Liefert Token/Tool/Confirm/Warning-Events und zuletzt genau ein {@code Done} oder {@code Error}.
     * Der Flux endet immer normal (nie mit onError), damit web/ den Fehler als SSE-Event senden kann.
     */
    public Flux<AssistantEvent> stream(long conversationId, String userText) {
        return Flux.<AssistantEvent>create(sink -> {
            SettingsSnapshot current;
            ChatClient client;
            List<ToolCallback> callbacks;
            try {
                current = settings.current();
                client = registry.clientFor(current.provider());
                callbacks = toolRegistry.enabledCallbacks();
            } catch (RuntimeException e) {
                fail(sink, null, e);
                return;
            }
            SettingsSnapshot s = current;

            List<ToolCallRecord> toolCalls = Collections.synchronizedList(new ArrayList<>());
            ToolInvocationListener listener = new ToolInvocationListener() {
                @Override
                public void onToolCalled(String toolName, String argumentsJson) {
                    sink.next(new AssistantEvent.ToolCalled(toolName, argumentsJson));
                }

                @Override
                public void onConfirmationRequired(UUID requestId, String toolName, String argumentsJson) {
                    sink.next(new AssistantEvent.ConfirmationRequired(requestId, toolName, argumentsJson));
                }

                @Override
                public void onToolCompleted(String toolName, String argumentsJson, String result) {
                    toolCalls.add(new ToolCallRecord(toolName, argumentsJson, result));
                }
            };
            AtomicBoolean ragWarned = new AtomicBoolean();
            // ToolCallingAdvisor durchläuft die Kette pro Tool-Runde neu: Warnung nur einmal pro Anfrage
            Consumer<String> ragWarning = message -> {
                if (ragWarned.compareAndSet(false, true)) {
                    sink.next(new AssistantEvent.Warning(message));
                }
            };

            StringBuilder fullText = new StringBuilder();
            long start = System.nanoTime();

            // Tool-Ausführung (ConfirmationGate blockiert bis zur Antwort des Nutzers) läuft im ToolCallingAdvisor
            // auf boundedElastic; subscribeOn hält zusätzlich den Aufrufer-Thread frei.
            var disposable = client.prompt()
                    .system(s.systemPrompt())
                    .user(userText)
                    .options(optionsFor(s))
                    .advisors(a -> a
                            .param(ChatMemory.CONVERSATION_ID, String.valueOf(conversationId))
                            .param(GuardedRagAdvisor.WARNING_SINK_KEY, ragWarning))
                    .toolCallbacks(callbacks)
                    .toolContext(Map.of(ToolInvocationListener.CONTEXT_KEY, listener))
                    .stream()
                    .chatResponse()
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            response -> {
                                String text = textOf(response);
                                if (!text.isEmpty()) {
                                    fullText.append(text);
                                    sink.next(new AssistantEvent.Token(text));
                                }
                            },
                            error -> fail(sink, s, error),
                            () -> {
                                if (fullText.toString().isBlank()) {
                                    log.warn("Leere Antwort vom Modell {} ({})", s.model(), s.provider());
                                    sink.next(new AssistantEvent.Error("Das Modell hat keine Antwort geliefert."));
                                    sink.complete();
                                    return;
                                }
                                long millis = (System.nanoTime() - start) / 1_000_000;
                                sink.next(new AssistantEvent.Done(fullText.toString(), s.provider(), s.model(),
                                        millis, List.copyOf(toolCalls)));
                                sink.complete();
                            });
            sink.onDispose(disposable);
        });
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /** Baut die provider-spezifischen Optionen; Thinking-Inhalte werden nicht als Token gestreamt. */
    private static ChatOptions.Builder<?> optionsFor(SettingsSnapshot s) {
        return switch (s.provider()) {
            case OLLAMA -> {
                OllamaChatOptions.Builder b = OllamaChatOptions.builder();
                b.model(s.model());
                b.temperature(s.temperature());
                if (s.thinkMode()) {
                    b.enableThinking();
                } else {
                    b.disableThinking();
                }
                yield b;
            }
            case ANTHROPIC -> {
                AnthropicChatOptions.Builder b = AnthropicChatOptions.builder();
                b.model(s.model());
                if (s.thinkMode()) {
                    // Extended Thinking erlaubt keine frei gewählte Temperatur
                    b.maxTokens(ANTHROPIC_MAX_TOKENS_THINKING);
                    b.thinkingEnabled(ANTHROPIC_THINKING_BUDGET);
                } else {
                    b.maxTokens(ANTHROPIC_MAX_TOKENS);
                    b.temperature(s.temperature());
                    b.thinkingDisabled();
                }
                yield b;
            }
        };
    }

    private static void fail(reactor.core.publisher.FluxSink<AssistantEvent> sink, SettingsSnapshot s, Throwable error) {
        log.warn("Anfrage fehlgeschlagen: {}", error.toString(), error);
        sink.next(new AssistantEvent.Error(userMessage(s == null ? null : s.provider(), error)));
        sink.complete();
    }

    static String userMessage(ChatProvider provider, Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ProviderUnavailableException p) {
                return p.getMessage();
            }
        }
        boolean claude = provider == ChatProvider.ANTHROPIC;
        if (isInChain(error, ConnectException.class, UnknownHostException.class)) {
            return claude
                    ? "Claude ist nicht erreichbar – bitte Internetverbindung und API-Key prüfen"
                    : "LLM-Server nicht erreichbar – in den Einstellungen kann Claude gewählt werden";
        }
        if (isInChain(error, SocketTimeoutException.class, TimeoutException.class)) {
            return "Zeitüberschreitung – " + (claude ? "Claude" : "der LLM-Server") + " antwortet nicht.";
        }
        int status = httpStatus(error);
        if ((status == 401 || status == 403) && claude) {
            return "Claude: API-Key ungültig";
        }
        if (status == 404) {
            return "Modell nicht gefunden – in den Einstellungen ein anderes Modell wählen";
        }
        if (status == 429) {
            return "Rate-Limit erreicht – bitte später erneut versuchen";
        }
        return "Die Anfrage ist fehlgeschlagen: " + rootMessage(error);
    }

    @SafeVarargs
    private static boolean isInChain(Throwable error, Class<? extends Throwable>... types) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** HTTP-Status aus Spring-RestClient-, WebClient- oder Anthropic-SDK-Fehlern, sonst -1. */
    private static int httpStatus(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RestClientResponseException r) {
                return r.getStatusCode().value();
            }
            if (t instanceof WebClientResponseException w) {
                return w.getStatusCode().value();
            }
            if (t instanceof AnthropicServiceException a) {
                return a.statusCode();
            }
        }
        return -1;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null && !root.getMessage().isBlank() ? root.getMessage() : root.getClass().getSimpleName();
    }
}
