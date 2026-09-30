package de.grauk.jarvis.status;

import java.lang.management.ManagementFactory;

import org.springframework.stereotype.Service;

/**
 * Minimaler Platzhalter aus dem KI-Kern (Brenner), damit das Tool getSystemStatus funktioniert.
 * TODO Hiebler: OSHI-Metriken, GPU aus voice-service /health, Latenz-Timer. {@link #snapshot()} bleibt Vertrag.
 */
@Service
public class MetricsService {

    /** cpuLoad 0..1 oder negativ, wenn unbekannt; gpu* null, solange nicht implementiert. */
    public record SystemSnapshot(double cpuLoad, long usedMemoryBytes, long totalMemoryBytes,
                                 Long gpuMemoryUsedBytes, Long gpuMemoryTotalBytes) {}

    public SystemSnapshot snapshot() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            long total = os.getTotalMemorySize();
            return new SystemSnapshot(os.getCpuLoad(), total - os.getFreeMemorySize(), total, null, null);
        }
        Runtime rt = Runtime.getRuntime();
        return new SystemSnapshot(-1, rt.totalMemory() - rt.freeMemory(), rt.maxMemory(), null, null);
    }
}
