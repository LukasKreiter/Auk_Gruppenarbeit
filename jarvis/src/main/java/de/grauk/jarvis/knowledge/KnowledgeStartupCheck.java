package de.grauk.jarvis.knowledge;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Räumt beim Start Reste eines unterbrochenen Betriebs auf und warnt bei fehlendem VectorStore. Gleicht außerdem
 * DB und VectorStore ab: Vektoren ohne DB-Zeile (nach einem Absturz mitten im Speichern/Löschen) würden sonst
 * weiter im RAG-Kontext auftauchen, ohne dass man sie im Dashboard sehen oder löschen kann.
 */
@Component
public class KnowledgeStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStartupCheck.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final KnowledgeDocumentRepository documents;
    private final MemoryFactRepository facts;
    private final KnowledgeProperties properties;
    private final VectorStore vectorStore;
    private final VectorStoreConfig vectorStoreConfig;

    public KnowledgeStartupCheck(KnowledgeDocumentRepository documents, MemoryFactRepository facts,
            KnowledgeProperties properties, VectorStore vectorStore, VectorStoreConfig vectorStoreConfig) {
        this.documents = documents;
        this.facts = facts;
        this.properties = properties;
        this.vectorStore = vectorStore;
        this.vectorStoreConfig = vectorStoreConfig;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        List<KnowledgeDocument> pending = documents.findAllByStatus(KnowledgeDocument.Status.PENDING);
        pending.forEach(d -> d.markFailed("Indexierung unterbrochen (Neustart)"));
        if (!pending.isEmpty()) {
            // evtl. schon hinzugefügte Chunks entfernen, sonst bleiben sie trotz FAILED durchsuchbar
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            for (KnowledgeDocument d : pending) {
                vectorStore.delete(b.eq("documentId", String.valueOf(d.getId())).build());
            }
            vectorStoreConfig.persist();
            documents.saveAll(pending);
            log.warn("{} Dokument(e) im Status PENDING auf FAILED gesetzt (Indexierung unterbrochen)", pending.size());
        }

        long indexed = documents.findAllByStatus(KnowledgeDocument.Status.INDEXED).size();
        long factCount = facts.count();
        if ((indexed > 0 || factCount > 0) && vectorStoreIsEmpty()) {
            log.error("Die Datenbank enthält {} indexierte Dokumente und {} Fakten, aber der VectorStore {} ist leer "
                    + "oder fehlt. Suche und Gedächtnis liefern nichts. Datei wiederherstellen oder Dokumente/Fakten "
                    + "neu anlegen.", indexed, factCount, properties.vectorStoreFile());
            return;
        }
        reconcile();
    }

    /** Ein leerer SimpleVectorStore wird als {@code {}} gespeichert. */
    private boolean vectorStoreIsEmpty() {
        File file = new File(properties.vectorStoreFile());
        return !file.isFile() || file.length() <= 2;
    }

    /**
     * Liest die gespeicherte VectorStore-Datei (ohne Embedding-Aufrufe), löscht Vektoren ohne DB-Zeile und warnt
     * vor Fakten ohne Vektor. Fehler hier verhindern den Start nicht.
     */
    void reconcile() {
        File file = new File(properties.vectorStoreFile());
        if (!file.isFile()) {
            return;
        }
        try {
            Set<String> documentIds = new HashSet<>();
            documents.findAll().forEach(d -> documentIds.add(String.valueOf(d.getId())));
            Set<String> factIds = new HashSet<>();
            facts.findAll().forEach(f -> factIds.add(String.valueOf(f.getId())));

            List<String> orphans = new ArrayList<>();
            Set<String> factsWithVector = new HashSet<>();
            for (Map.Entry<String, JsonNode> entry : MAPPER.readTree(file).properties()) {
                JsonNode metadata = entry.getValue().path("metadata");
                String type = metadata.path("type").asString("");
                if (type.equals("document") && !documentIds.contains(metadata.path("documentId").asString(""))) {
                    orphans.add(entry.getKey());
                } else if (type.equals("fact")) {
                    String factId = metadata.path("factId").asString("");
                    if (factIds.contains(factId)) {
                        factsWithVector.add(factId);
                    } else {
                        orphans.add(entry.getKey());
                    }
                }
            }

            if (!orphans.isEmpty()) {
                vectorStore.delete(orphans);
                vectorStoreConfig.persist();
                log.warn("{} verwaiste Vektoren ohne Dokument/Fakt in der Datenbank entfernt", orphans.size());
            }
            Set<String> missing = new TreeSet<>(factIds);
            missing.removeAll(factsWithVector);
            if (!missing.isEmpty()) {
                log.warn("{} Fakt(en) ohne Vektor (Ids {}): werden bei der Suche nicht gefunden. Im Dashboard löschen "
                        + "und neu anlegen.", missing.size(), missing);
            }
        } catch (RuntimeException e) {
            log.error("Abgleich von Datenbank und VectorStore {} fehlgeschlagen", file, e);
        }
    }
}
