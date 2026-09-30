package de.grauk.jarvis.knowledge;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stellt den einzigen {@link VectorStore} bereit (SimpleVectorStore, Embeddings immer bge-m3, ADR 0003/0004)
 * und speichert ihn als JSON-Datei.
 */
@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    private final KnowledgeProperties properties;
    private volatile SimpleVectorStore store;

    public VectorStoreConfig(KnowledgeProperties properties) {
        this.properties = properties;
    }

    /**
     * Lädt eine vorhandene Datei. Ist sie nicht lesbar, bricht der Start bewusst ab: ein stiller Neustart mit
     * leerem Store würde beim nächsten {@link #persist()} die (evtl. reparierbare) Datei überschreiben.
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore created = SimpleVectorStore.builder(embeddingModel).build();
        File file = new File(properties.vectorStoreFile()).getAbsoluteFile();
        if (file.isFile()) {
            try {
                created.load(file);
                log.info("VectorStore aus {} geladen", file);
            } catch (RuntimeException e) {
                throw new IllegalStateException("VectorStore-Datei " + file + " ist beschädigt. Bitte prüfen, "
                        + "reparieren oder (zusammen mit den Dokumenten) löschen und neu indexieren.", e);
            }
        }
        this.store = created;
        return created;
    }

    /** Schreibt den Store crash-sicher (Temp-Datei, dann atomarer Move). */
    public synchronized void persist() {
        SimpleVectorStore current = store;
        if (current == null) {
            throw new IllegalStateException("VectorStore ist noch nicht initialisiert");
        }
        Path target = Path.of(properties.vectorStoreFile()).toAbsolutePath();
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), "vector-store", ".tmp");
            try {
                current.save(tmp.toFile());
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new KnowledgeException("VectorStore konnte nicht gespeichert werden: " + e.getMessage(), e);
        }
    }
}
