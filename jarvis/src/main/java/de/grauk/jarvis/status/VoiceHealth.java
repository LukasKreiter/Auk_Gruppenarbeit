package de.grauk.jarvis.status;

import java.time.Instant;
import java.util.List;

/**
 * Ergebnis eines Aufrufs von {@code GET /health} des voice-service. GPU-Werte sind {@code null}, wenn der
 * voice-service sie nicht (oder nicht lesbar) liefert. {@code gpuUtilization} ist 0..1.
 */
public record VoiceHealth(boolean up, String message, List<String> models, String gpuName,
                          Long gpuMemoryUsedBytes, Long gpuMemoryTotalBytes, Double gpuUtilization,
                          Instant checkedAt) {

    public static VoiceHealth down(String message, Instant at) {
        return new VoiceHealth(false, message, List.of(), null, null, null, null, at);
    }

    public boolean hasGpuMemory() {
        return gpuMemoryUsedBytes != null && gpuMemoryTotalBytes != null;
    }
}
