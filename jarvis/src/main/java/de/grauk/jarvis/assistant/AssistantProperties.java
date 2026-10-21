package de.grauk.jarvis.assistant;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Konfiguration unter {@code jarvis.assistant.*}. */
@ConfigurationProperties("jarvis.assistant")
public record AssistantProperties(ChatProvider defaultProvider, List<String> anthropicModels, Integer memoryWindow) {

    public AssistantProperties {
        defaultProvider = defaultProvider == null ? ChatProvider.OLLAMA : defaultProvider;
        anthropicModels = anthropicModels == null ? List.of() : List.copyOf(anthropicModels);
        memoryWindow = memoryWindow == null ? 20 : memoryWindow;
    }
}
