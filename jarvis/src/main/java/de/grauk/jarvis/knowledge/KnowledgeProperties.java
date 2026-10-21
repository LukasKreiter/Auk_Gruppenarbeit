package de.grauk.jarvis.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Konfiguration {@code jarvis.knowledge.*}. */
@ConfigurationProperties("jarvis.knowledge")
public record KnowledgeProperties(
        @DefaultValue("./data/vector-store.json") String vectorStoreFile,
        @DefaultValue("0.6") double similarityThreshold,
        @DefaultValue("4") int topK) {
}
