package de.grauk.jarvis.status;

import java.util.List;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ChatProviderRegistry;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Ampel des LLM-Servers. Nutzt die (kurz gecachte) Abfrage der {@link ChatProviderRegistry}, kein eigener Probe. */
@Component
public class OllamaHealthIndicator implements HealthIndicator {

    private final ChatProviderRegistry registry;

    public OllamaHealthIndicator(ChatProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        List<String> models = registry.models(ChatProvider.OLLAMA);
        if (Boolean.TRUE.equals(registry.available().get(ChatProvider.OLLAMA))) {
            return Health.up().withDetail("models", models).build();
        }
        return Health.down().withDetail("message", "LLM-Server nicht erreichbar").build();
    }
}
