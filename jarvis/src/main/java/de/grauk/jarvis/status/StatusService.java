package de.grauk.jarvis.status;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.sql.DataSource;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ChatProviderRegistry;
import de.grauk.jarvis.status.MetricsService.Latencies;
import de.grauk.jarvis.status.MetricsService.SystemSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Service;

/**
 * Fasst Ampeln, Modelle, GPU, CPU/RAM und Latenzen für die Statusseite und {@code GET /api/status} zusammen.
 * Jeder Teilwert wird einzeln abgesichert: ein werfender Indikator ergibt eine rote Ampel bzw. einen leeren Wert,
 * nie einen Fehler für den gesamten Bericht.
 */
@Service
public class StatusService {

    private static final Logger log = LoggerFactory.getLogger(StatusService.class);

    public enum State { UP, DOWN, UNKNOWN }

    /** {@code key}: ollama | claude | voice | db. */
    public record Light(String key, String name, State state, String message) {}

    public record Gpu(String name, Long memoryUsedBytes, Long memoryTotalBytes, Double utilization) {}

    /**
     * @param ollamaModels auf dem LLM-Server installierte Modelle ({@code /api/tags}), leer wenn nicht erreichbar
     * @param voiceModels  vom voice-service gemeldete Modelle
     * @param gpu          {@code null}, wenn der voice-service keine GPU meldet
     * @param cpuLoad      0..1, negativ = unbekannt
     * @param metricsSource Quelle von CPU/RAM: {@code oshi}, {@code jmx} oder {@code unbekannt}
     */
    public record StatusReport(Instant checkedAt, ChatProvider activeProvider, List<Light> lights,
                               List<String> ollamaModels, List<String> voiceModels, Gpu gpu, double cpuLoad,
                               long usedMemoryBytes, long totalMemoryBytes, Latencies latencies,
                               String metricsSource) {

        /** Kompatibilitäts-Konstruktor ohne Quellenangabe. */
        public StatusReport(Instant checkedAt, ChatProvider activeProvider, List<Light> lights,
                            List<String> ollamaModels, List<String> voiceModels, Gpu gpu, double cpuLoad,
                            long usedMemoryBytes, long totalMemoryBytes, Latencies latencies) {
            this(checkedAt, activeProvider, lights, ollamaModels, voiceModels, gpu, cpuLoad, usedMemoryBytes,
                    totalMemoryBytes, latencies, "unbekannt");
        }
    }

    private final OllamaHealthIndicator ollama;
    private final ClaudeHealthIndicator claude;
    private final VoiceHealthIndicator voiceIndicator;
    private final VoiceHealthClient voice;
    private final ChatProviderRegistry registry;
    private final MetricsService metrics;
    private final DataSource dataSource;

    /** Letzter Fehlerzustand je Teilwert, damit nur bei Zustandswechsel geloggt wird. */
    private final Map<String, String> failing = new ConcurrentHashMap<>();

    public StatusService(OllamaHealthIndicator ollama, ClaudeHealthIndicator claude,
                         VoiceHealthIndicator voiceIndicator, VoiceHealthClient voice,
                         ChatProviderRegistry registry, MetricsService metrics, DataSource dataSource) {
        this.ollama = ollama;
        this.claude = claude;
        this.voiceIndicator = voiceIndicator;
        this.voice = voice;
        this.registry = registry;
        this.metrics = metrics;
        this.dataSource = dataSource;
    }

    public StatusReport report() {
        ChatProvider active = guard("provider", () -> registry.active(), null);

        Health o = guardHealth("ollama", ollama::health);
        Light ollamaLight = light("ollama", "Ollama (LLM-Server)", o);
        Light claudeLight = light("claude", "Claude", guardHealth("claude", claude::health));
        if (active == ChatProvider.ANTHROPIC && claudeLight.state() == State.UNKNOWN) {
            claudeLight = new Light("claude", "Claude", State.DOWN, "aktiver Provider nicht verfügbar (kein API-Key)");
        }
        Light voiceLight = light("voice", "voice-service", guardHealth("voice", voiceIndicator::health));
        List<Light> lights = List.of(ollamaLight, claudeLight, voiceLight, database());

        VoiceHealth v = guard("voice-details", voice::current, null);
        Gpu gpu = null;
        List<String> voiceModels = List.of();
        if (v != null) {
            voiceModels = v.models() != null ? v.models() : List.of();
            gpu = v.gpuName() != null || v.hasGpuMemory() || v.gpuUtilization() != null
                    ? new Gpu(v.gpuName(), v.gpuMemoryUsedBytes(), v.gpuMemoryTotalBytes(), v.gpuUtilization()) : null;
        }

        SystemSnapshot s = guard("metrics", metrics::snapshot, new SystemSnapshot(-1, 0, 0, null, null));
        List<String> ollamaModels = o.getStatus() == Status.UP
                ? guard("ollama-models", () -> registry.models(ChatProvider.OLLAMA), List.<String>of()) : List.of();
        Latencies latencies = guard("latencies", metrics::lastLatencies, new Latencies(null, null, null));
        String source = guard("metrics-source", metrics::source, "unbekannt");
        return new StatusReport(Instant.now(), active, lights, ollamaModels, voiceModels, gpu,
                s.cpuLoad(), s.usedMemoryBytes(), s.totalMemoryBytes(), latencies, source);
    }

    private static Light light(String key, String name, Health h) {
        State state = h.getStatus() == Status.UP ? State.UP : h.getStatus() == Status.DOWN ? State.DOWN : State.UNKNOWN;
        Object msg = h.getDetails().get("message");
        return new Light(key, name, state, msg != null ? msg.toString() : state == State.UP ? "erreichbar" : "");
    }

    private Health guardHealth(String key, Supplier<Health> supplier) {
        try {
            Health h = supplier.get();
            if (h == null) {
                throw new IllegalStateException("Indikator lieferte null");
            }
            failing.remove(key);
            return h;
        } catch (RuntimeException e) {
            noteFailure(key, e);
            return Health.down().withDetail("message", "Prüfung fehlgeschlagen (" + e.getClass().getSimpleName() + ")")
                    .build();
        }
    }

    private <T> T guard(String key, Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            failing.remove(key);
            return value != null ? value : fallback;
        } catch (RuntimeException e) {
            noteFailure(key, e);
            return fallback;
        }
    }

    /** Loggt nur beim Wechsel in den Fehlerzustand (oder bei geänderter Fehlerart), dann mit Stacktrace. */
    private void noteFailure(String key, RuntimeException e) {
        String previous = failing.put(key, e.getClass().getName());
        if (!e.getClass().getName().equals(previous)) {
            log.warn("Status-Teilwert '{}' fehlgeschlagen", key, e);
        }
    }

    private Light database() {
        try (Connection c = dataSource.getConnection()) {
            boolean valid = c.isValid(1);
            if (valid) {
                failing.remove("db");
                return new Light("db", "Datenbank", State.UP, "erreichbar");
            }
            if (failing.put("db", "invalid") == null) {
                log.warn("Datenbankverbindung ungültig");
            }
            return new Light("db", "Datenbank", State.DOWN, "Verbindung ungültig");
        } catch (Exception e) {
            noteFailure("db", e instanceof RuntimeException re ? re : new RuntimeException(e));
            return new Light("db", "Datenbank", State.DOWN, "nicht erreichbar (" + e.getClass().getSimpleName() + ")");
        }
    }
}
