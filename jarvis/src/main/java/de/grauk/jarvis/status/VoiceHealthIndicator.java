package de.grauk.jarvis.status;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Health-Ampel des voice-service (Bean-Name {@code voiceHealthIndicator} → Komponente {@code voice}). */
@Component
public class VoiceHealthIndicator implements HealthIndicator {

    private final VoiceHealthClient client;

    public VoiceHealthIndicator(VoiceHealthClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        VoiceHealth v = client.current();
        Health.Builder b = v.up() ? Health.up() : Health.down();
        b.withDetail("message", v.message());
        if (!v.models().isEmpty()) {
            b.withDetail("models", v.models());
        }
        if (v.gpuName() != null) {
            b.withDetail("gpuName", v.gpuName());
        }
        if (v.gpuMemoryUsedBytes() != null) {
            b.withDetail("gpuMemoryUsedBytes", v.gpuMemoryUsedBytes());
        }
        if (v.gpuMemoryTotalBytes() != null) {
            b.withDetail("gpuMemoryTotalBytes", v.gpuMemoryTotalBytes());
        }
        if (v.gpuUtilization() != null) {
            b.withDetail("gpuUtilization", v.gpuUtilization());
        }
        return b.build();
    }
}
