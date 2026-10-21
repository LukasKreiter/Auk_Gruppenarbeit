package de.grauk.jarvis.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ruft {@code GET <jarvis.voice.base-url>/health} des voice-service auf (Header {@code X-Jarvis-Token}, wenn
 * {@code jarvis.voice.token} gesetzt ist) und cacht das Ergebnis kurz, weil die Statusseite alle 5 s pollt.
 * {@link VoiceHealthIndicator} und {@link MetricsService} benutzen denselben Aufruf.
 * Das Token wird weder geloggt noch weitergegeben. Das Antwortformat wird tolerant geparst
 * (siehe docs/CODE-INFRASTRUKTUR.md, Abschnitt status/).
 */
@Component
public class VoiceHealthClient {

    static final String TOKEN_HEADER = "X-Jarvis-Token";
    private static final Logger log = LoggerFactory.getLogger(VoiceHealthClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CACHE_TTL = Duration.ofSeconds(3);
    private static final double MIB = 1024.0 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient client;
    private final String token;
    private final Clock clock;
    private final Duration cacheTtl;

    /** Leere/nicht-JSON-Antwort: vermutlich ein anderer Dienst oder eine Proxy-Fehlerseite auf dem Port → down. */
    private static final String UNREADABLE = "Antwort nicht lesbar (kein voice-service?)";

    private final AtomicBoolean unreadableLogged = new AtomicBoolean();

    // Zugriff nur unter synchronized(this)
    private VoiceHealth cached;
    private Instant cachedAt;
    private Boolean lastUp;
    private Instant downSince;
    private CompletableFuture<VoiceHealth> inFlight;

    @Autowired
    public VoiceHealthClient(@Value("${jarvis.voice.base-url}") String baseUrl,
                             @Value("${jarvis.voice.token:}") String token) {
        this(timeoutBuilder(), baseUrl, token, Clock.systemUTC(), CACHE_TTL);
    }

    VoiceHealthClient(RestClient.Builder builder, String baseUrl, String token, Clock clock, Duration cacheTtl) {
        this.client = builder.baseUrl(baseUrl).build();
        this.token = token == null ? "" : token.strip();
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }

    private static RestClient.Builder timeoutBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        return RestClient.builder().requestFactory(factory);
    }

    /**
     * Letztes Ergebnis, höchstens {@code cacheTtl} alt; fragt sonst neu ab. Wirft nie. Der Netzwerkaufruf läuft nicht
     * unter einem Lock (Single-Flight): während ein Abruf läuft, bekommen andere Aufrufer den letzten Wert, oder
     * (ohne letzten Wert) warten auf dasselbe Ergebnis.
     */
    public VoiceHealth current() {
        CompletableFuture<VoiceHealth> wait;
        Instant now;
        synchronized (this) {
            now = clock.instant();
            if (cached != null && Duration.between(cachedAt, now).compareTo(cacheTtl) < 0) {
                return cached;
            }
            if (inFlight != null) {
                if (cached != null) {
                    return cached;
                }
                wait = inFlight;
            } else {
                inFlight = new CompletableFuture<>();
                wait = null;
            }
        }
        if (wait != null) {
            return wait.join();
        }
        VoiceHealth result;
        try {
            result = fetch(now);
        } catch (RuntimeException | Error e) {
            result = VoiceHealth.down("nicht erreichbar (" + e.getClass().getSimpleName() + ")", now);
        }
        CompletableFuture<VoiceHealth> done;
        synchronized (this) {
            result = withDownSince(result, now);
            cached = result;
            cachedAt = now;
            boolean transition = lastUp == null || lastUp != result.up();
            lastUp = result.up();
            done = inFlight;
            inFlight = null;
            if (transition) {
                if (result.up()) {
                    log.info("voice-service erreichbar");
                } else {
                    log.warn("voice-service nicht gesund: {}", result.message());
                }
            }
        }
        done.complete(result);
        return result;
    }

    /** Merkt sich den Beginn einer Ausfallphase und hängt „down seit &lt;Zeit&gt;“ an die Meldung. Unter Lock aufrufen. */
    private VoiceHealth withDownSince(VoiceHealth h, Instant now) {
        if (h.up()) {
            downSince = null;
            return h;
        }
        if (downSince == null) {
            downSince = now;
        }
        String since = DateTimeFormatter.ofPattern("HH:mm:ss").format(downSince.atZone(clock.getZone()));
        return new VoiceHealth(false, h.message() + ", down seit " + since, h.models(), h.gpuName(),
                h.gpuMemoryUsedBytes(), h.gpuMemoryTotalBytes(), h.gpuUtilization(), h.checkedAt());
    }

    private VoiceHealth fetch(Instant now) {
        try {
            String body = client.get().uri("/health").headers(h -> {
                if (!token.isEmpty()) {
                    h.set(TOKEN_HEADER, token);
                }
            }).retrieve().body(String.class);
            VoiceHealth h = parse(body, now);
            // einmal pro Übergang melden: nach einer lesbaren Antwort wird ein erneuter Fall wieder geloggt
            if (!UNREADABLE.equals(h.message())) {
                unreadableLogged.set(false);
            } else if (unreadableLogged.compareAndSet(false, true)) {
                log.warn("voice-service /health lieferte eine nicht lesbare oder leere Antwort; gilt als down");
            }
            return h;
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            logCause(e);
            return VoiceHealth.down(code == 401 || code == 403 ? "Token ungültig (HTTP " + code + ")" : "HTTP " + code, now);
        } catch (ResourceAccessException e) {
            logCause(e);
            return VoiceHealth.down("Timeout/Verbindung fehlgeschlagen", now);
        } catch (RuntimeException e) {
            logCause(e);
            // nur der Klassenname: Exception-Texte könnten Request-Details enthalten
            return VoiceHealth.down("nicht erreichbar (" + e.getClass().getSimpleName() + ")", now);
        }
    }

    /** Ursache auf debug, ohne Query-Teil der URL und ohne Token. */
    private void logCause(RuntimeException e) {
        if (log.isDebugEnabled()) {
            String text = e.toString().replaceAll("\\?\\S*", "?<entfernt>");
            if (!token.isEmpty()) {
                text = text.replace(token, "***");
            }
            log.debug("voice-service /health fehlgeschlagen: {}", text);
        }
    }

    static VoiceHealth parse(String body, Instant now) {
        if (body == null || body.isBlank()) {
            return new VoiceHealth(false, UNREADABLE, List.of(), null, null, null, null, now);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            return new VoiceHealth(false, UNREADABLE, List.of(), null, null, null, null, now);
        }
        if (root == null || root.isMissingNode()) {
            return new VoiceHealth(false, UNREADABLE, List.of(), null, null, null, null, now);
        }
        boolean up = true;
        String message = "ok";
        JsonNode status = root.get("status");
        if (status != null && status.isString()) {
            String s = status.asString().toLowerCase();
            if (!(s.equals("ok") || s.equals("up") || s.equals("healthy"))) {
                up = false;
                message = "Status: " + status.asString();
            }
        }
        JsonNode gpu = root.get("gpu");
        if (gpu != null && gpu.isArray()) {
            gpu = gpu.isEmpty() ? null : gpu.get(0);
        }
        String gpuName = null;
        Long used = null;
        Long total = null;
        Double util = null;
        if (gpu != null && gpu.isObject()) {
            gpuName = text(gpu, "name");
            used = bytes(gpu, "memory_used", "vram_used");
            total = bytes(gpu, "memory_total", "vram_total");
            util = utilization(gpu);
        }
        return new VoiceHealth(up, message, models(root.get("models")), gpuName, used, total, util, now);
    }

    private static List<String> models(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        if (node.isString()) {
            result.add(node.asString());
        } else if (node.isArray()) {
            node.forEach(n -> result.add(n.isString() ? n.asString() : n.toString()));
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                result.add(e.getKey() + ": " + (e.getValue().isString() ? e.getValue().asString() : e.getValue().toString()));
            }
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    /** Liest {@code <prefix>_bytes} oder {@code <prefix>_mb} (MiB). */
    private static Long bytes(JsonNode node, String... prefixes) {
        for (String p : prefixes) {
            JsonNode b = node.get(p + "_bytes");
            if (b != null && b.isNumber()) {
                return b.asLong();
            }
            JsonNode mb = node.get(p + "_mb");
            if (mb != null && mb.isNumber()) {
                return (long) (mb.asDouble() * MIB);
            }
        }
        return null;
    }

    /** {@code utilization_percent} (0..100) oder {@code utilization} (0..1; Werte über 1 gelten als Prozent). */
    private static Double utilization(JsonNode gpu) {
        JsonNode p = gpu.get("utilization_percent");
        if (p != null && p.isNumber()) {
            return clamp(p.asDouble() / 100);
        }
        JsonNode u = gpu.get("utilization");
        if (u != null && u.isNumber()) {
            double v = u.asDouble();
            return clamp(v > 1 ? v / 100 : v);
        }
        return null;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
