package de.grauk.jarvis.status;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ChatProviderRegistry;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Claude: nur „Key vorhanden“, kein Netzwerkaufruf. Ohne Key {@code UNKNOWN} (nie {@code DOWN}), weil Claude
 * optional ist und {@code UNKNOWN} den Gesamtstatus nicht verschlechtert.
 */
@Component
public class ClaudeHealthIndicator implements HealthIndicator {

    private final ChatProviderRegistry registry;

    public ClaudeHealthIndicator(ChatProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        if (Boolean.TRUE.equals(registry.available().get(ChatProvider.ANTHROPIC))) {
            return Health.up().withDetail("message", "konfiguriert").build();
        }
        return Health.unknown().withDetail("message", "kein Key").build();
    }
}
