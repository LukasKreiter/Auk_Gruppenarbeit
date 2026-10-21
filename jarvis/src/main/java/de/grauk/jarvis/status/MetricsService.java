package de.grauk.jarvis.status;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;

/**
 * Systemmetriken und Latenzen. CPU/RAM über OSHI, GPU aus dem (gecachten) {@code /health} des voice-service,
 * Latenzen als Micrometer-Timer {@code jarvis.latency} (Tag {@code stage=stt|llm|tts}) plus je letzter Wert.
 * {@link #snapshot()} ist der Vertrag für {@code tools/builtin/SystemTools}.
 */
@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);
    private static final long CPU_SAMPLE_NANOS = Duration.ofSeconds(1).toNanos();

    /**
     * cpuLoad 0..1 oder negativ, wenn unbekannt; Speicherwerte negativ, wenn unbekannt; gpu* null, wenn der
     * voice-service keine GPU-Werte liefert.
     */
    public record SystemSnapshot(double cpuLoad, long usedMemoryBytes, long totalMemoryBytes,
                                 Long gpuMemoryUsedBytes, Long gpuMemoryTotalBytes) {}

    /** Letzte gemessene Latenzen in ms; {@code null} = noch keine. */
    public record Latencies(Long sttMs, Long llmMs, Long ttsMs) {}

    private final Timer sttTimer;
    private final Timer llmTimer;
    private final Timer ttsTimer;
    private final VoiceHealthClient voice;

    private volatile Long lastStt;
    private volatile Long lastLlm;
    private volatile Long lastTts;

    private final SystemInfo systemInfo;
    private final CentralProcessor processor;
    private long[] prevTicks;
    private long prevTicksAt;
    private double lastCpuLoad = -1;
    private final AtomicBoolean oshiRuntimeFailureLogged = new AtomicBoolean();
    private volatile String source = "unbekannt";

    public MetricsService(MeterRegistry registry, VoiceHealthClient voice) {
        this.voice = voice;
        this.sttTimer = timer(registry, "stt");
        this.llmTimer = timer(registry, "llm");
        this.ttsTimer = timer(registry, "tts");
        SystemInfo info = null;
        CentralProcessor cpu = null;
        try {
            info = new SystemInfo();
            cpu = info.getHardware().getProcessor();
            prevTicks = cpu.getSystemCpuLoadTicks();
            prevTicksAt = System.nanoTime();
        } catch (RuntimeException | LinkageError e) {
            log.warn("OSHI nicht verfügbar, verwende JMX-Werte: {}", e.toString());
            info = null;
            cpu = null;
        }
        this.systemInfo = info;
        this.processor = cpu;
    }

    private static Timer timer(MeterRegistry registry, String stage) {
        return Timer.builder("jarvis.latency").description("Latenz je Stufe der Sprachpipeline")
                .tag("stage", stage).register(registry);
    }

    public void recordStt(long ms) {
        sttTimer.record(ms, TimeUnit.MILLISECONDS);
        lastStt = ms;
    }

    public void recordLlm(long ms) {
        llmTimer.record(ms, TimeUnit.MILLISECONDS);
        lastLlm = ms;
    }

    public void recordTts(long ms) {
        ttsTimer.record(ms, TimeUnit.MILLISECONDS);
        lastTts = ms;
    }

    public Latencies lastLatencies() {
        return new Latencies(lastStt, lastLlm, lastTts);
    }

    /** Setzt die letzten Latenzen (z. B. aus der DB beim Start), ohne die Timer zu berühren; bereits gemessene Werte bleiben. */
    void restoreLatencies(Long stt, Long llm, Long tts) {
        if (stt != null && lastStt == null) lastStt = stt;
        if (llm != null && lastLlm == null) lastLlm = llm;
        if (tts != null && lastTts == null) lastTts = tts;
    }

    public SystemSnapshot snapshot() {
        VoiceHealth gpu = voice.current();
        Long gpuUsed = gpu.hasGpuMemory() ? gpu.gpuMemoryUsedBytes() : null;
        Long gpuTotal = gpu.hasGpuMemory() ? gpu.gpuMemoryTotalBytes() : null;
        if (systemInfo != null) {
            try {
                GlobalMemory mem = systemInfo.getHardware().getMemory();
                long total = mem.getTotal();
                SystemSnapshot s = new SystemSnapshot(sanitize(cpuLoad()), total - mem.getAvailable(), total,
                        gpuUsed, gpuTotal);
                source = "oshi";
                return s;
            } catch (RuntimeException | LinkageError e) {
                if (oshiRuntimeFailureLogged.compareAndSet(false, true)) {
                    log.warn("OSHI-Abfrage fehlgeschlagen, weiche auf JMX aus (weitere Fehler auf debug)", e);
                } else {
                    log.debug("OSHI-Abfrage fehlgeschlagen: {}", e.toString());
                }
            }
        }
        source = "jmx";
        return jmx(gpuUsed, gpuTotal);
    }

    /** Quelle der zuletzt gelieferten CPU/RAM-Werte: {@code oshi}, {@code jmx} oder {@code unbekannt}. */
    public String source() {
        return source;
    }

    /** NaN und negative Werte bedeuten „unbekannt“ (-1); Werte über 1 werden auf 1 begrenzt. */
    static double sanitize(double load) {
        if (Double.isNaN(load) || load < 0) {
            return -1;
        }
        return Math.min(1, load);
    }

    /** Last seit der vorigen Messung (Tick-Differenz), höchstens einmal pro Sekunde neu berechnet, nie blockierend. */
    private synchronized double cpuLoad() {
        if (processor == null) {
            return -1;
        }
        long now = System.nanoTime();
        if (now - prevTicksAt >= CPU_SAMPLE_NANOS) {
            lastCpuLoad = processor.getSystemCpuLoadBetweenTicks(prevTicks);
            prevTicks = processor.getSystemCpuLoadTicks();
            prevTicksAt = now;
        }
        return lastCpuLoad;
    }

    private static SystemSnapshot jmx(Long gpuUsed, Long gpuTotal) {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            long total = os.getTotalMemorySize();
            return new SystemSnapshot(sanitize(os.getCpuLoad()),total - os.getFreeMemorySize(), total, gpuUsed, gpuTotal);
        }
        Runtime rt = Runtime.getRuntime();
        return new SystemSnapshot(-1, rt.totalMemory() - rt.freeMemory(), rt.maxMemory(), gpuUsed, gpuTotal);
    }
}
