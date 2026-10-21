package de.grauk.jarvis.web;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import de.grauk.jarvis.tools.TimerExpiredEvent;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dauerhafter SSE-Kanal fürs Dashboard ({@code GET /api/events}). Alle offenen Browser-Tabs sind hier angemeldet;
 * Server-Ereignisse (derzeit nur ein abgelaufener Timer) gehen an alle. Es sind höchstens {@value #DEFAULT_MAX_EMITTERS}
 * Clients gleichzeitig angemeldet (der älteste wird geschlossen, der Browser verbindet sich selbst neu). Alle 30 s geht ein
 * SSE-Kommentar an alle Clients; dabei werden tote Emitter entfernt, auch wenn kein Ereignis anliegt.
 * Ein Timer, der abläuft, während kein Tab offen ist, wird vorgemerkt und an den nächsten Client nachgeliefert.
 */
@Component
public class DashboardEvents {

    private static final Logger log = LoggerFactory.getLogger(DashboardEvents.class);

    static final int DEFAULT_MAX_EMITTERS = 20;
    static final long HEARTBEAT_SECONDS = 30;
    static final int MAX_PENDING_TIMERS = 20;

    /** Nutzdaten des SSE-Events {@code timer}. */
    public record TimerPayload(UUID timerId, String label, int minutes, Instant expiredAt) {}

    /** Behält die Anmeldereihenfolge (ältester zuerst). */
    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();
    private final JsonMapper json;
    private final int maxEmitters;
    /** Abgelaufene Timer, die noch keinen Client erreicht haben (älteste zuerst). */
    private final Deque<TimerPayload> pendingTimers = new ArrayDeque<>();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "dashboard-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public DashboardEvents(JsonMapper json) {
        this(json, DEFAULT_MAX_EMITTERS);
    }

    DashboardEvents(JsonMapper json, int maxEmitters) {
        this.json = json;
        this.maxEmitters = maxEmitters;
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** Meldet einen neuen Client an; der Kanal hat kein Timeout und endet, wenn der Browser die Verbindung schließt. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        evictOldest();
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            // macht den offenen Kanal im Browser sofort sichtbar (EventSource.onopen) und prüft die Verbindung
            emitter.send(SseEmitter.event().name("ready").data("{}"));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
            log.debug("Dashboard-Client sofort getrennt: {}", e.toString());
            return emitter;
        }
        deliverPendingTimers(emitter);
        return emitter;
    }

    private void deliverPendingTimers(SseEmitter emitter) {
        List<TimerPayload> pending;
        synchronized (pendingTimers) {
            pending = List.copyOf(pendingTimers);
            pendingTimers.clear();
        }
        for (int i = 0; i < pending.size(); i++) {
            try {
                emitter.send(SseEmitter.event().name("timer").data(json.writeValueAsString(pending.get(i))));
                log.info("Timer '{}' an neu verbundenen Dashboard-Client nachgeliefert", pending.get(i).label());
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
                pending.subList(i, pending.size()).forEach(this::remember);
                log.debug("Dashboard-Client beim Nachliefern getrennt: {}", e.toString());
                return;
            }
        }
    }

    private void remember(TimerPayload payload) {
        synchronized (pendingTimers) {
            if (pendingTimers.size() >= MAX_PENDING_TIMERS) {
                TimerPayload dropped = pendingTimers.removeFirst();
                log.warn("Zu viele nicht zugestellte Timer, verwerfe '{}' (abgelaufen {})", dropped.label(),
                        dropped.expiredAt());
            }
            pendingTimers.addLast(payload);
        }
    }

    int pendingTimerCount() {
        synchronized (pendingTimers) {
            return pendingTimers.size();
        }
    }

    private void evictOldest() {
        while (emitters.size() >= maxEmitters) {
            SseEmitter oldest = emitters.iterator().next();
            if (emitters.remove(oldest)) {
                log.info("Dashboard-Clients an der Obergrenze ({}), ältesten Client geschlossen", maxEmitters);
                try {
                    oldest.complete();
                } catch (RuntimeException e) {
                    log.debug("Ältester Dashboard-Client war schon beendet: {}", e.toString());
                }
            }
        }
    }

    public int subscriberCount() {
        return emitters.size();
    }

    @EventListener
    public void onTimerExpired(TimerExpiredEvent event) {
        log.info("Timer '{}' ({} min) abgelaufen, {} Dashboard-Clients", event.label(), event.minutes(), emitters.size());
        TimerPayload payload = new TimerPayload(event.timerId(), event.label(), event.minutes(), event.expiredAt());
        int delivered = broadcast("timer", payload);
        if (delivered == 0) {
            log.warn("Timer '{}' abgelaufen, aber kein Dashboard-Client erreichbar – wird beim nächsten Öffnen "
                    + "nachgeliefert", event.label());
            remember(payload);
        }
    }

    /** Sendet an alle Clients, entfernt nicht erreichbare und liefert die Zahl der erreichten. */
    int broadcast(String name, Object payload) {
        String data;
        try {
            data = json.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.error("Dashboard-Event '{}' konnte nicht serialisiert werden", name, e);
            return 0;
        }
        return deliver(() -> SseEmitter.event().name(name).data(data));
    }

    /** Hält Verbindungen offen und entfernt tote Emitter. Läuft alle {@value #HEARTBEAT_SECONDS} s. */
    void heartbeat() {
        try {
            deliver(() -> SseEmitter.event().comment("keepalive"));
        } catch (RuntimeException e) {
            log.warn("Dashboard-Heartbeat fehlgeschlagen", e);
        }
    }

    private int deliver(Supplier<SseEmitter.SseEventBuilder> event) {
        int delivered = 0;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event.get());
                delivered++;
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
                log.debug("Dashboard-Client entfernt: {}", e.toString());
            }
        }
        return delivered;
    }
}
