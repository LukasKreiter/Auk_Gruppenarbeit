package de.grauk.jarvis.knowledge;

import java.io.File;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Räumt beim Start Reste eines unterbrochenen Betriebs auf und warnt bei fehlendem VectorStore. */
@Component
public class KnowledgeStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStartupCheck.class);

    private final KnowledgeDocumentRepository documents;
    private final MemoryFactRepository facts;
    private final KnowledgeProperties properties;

    public KnowledgeStartupCheck(KnowledgeDocumentRepository documents, MemoryFactRepository facts,
            KnowledgeProperties properties) {
        this.documents = documents;
        this.facts = facts;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        List<KnowledgeDocument> pending = documents.findAllByStatus(KnowledgeDocument.Status.PENDING);
        pending.forEach(d -> d.markFailed("Indexierung unterbrochen (Neustart)"));
        if (!pending.isEmpty()) {
            documents.saveAll(pending);
            log.warn("{} Dokument(e) im Status PENDING auf FAILED gesetzt (Indexierung unterbrochen)", pending.size());
        }

        long indexed = documents.findAllByStatus(KnowledgeDocument.Status.INDEXED).size();
        long factCount = facts.count();
        if ((indexed > 0 || factCount > 0) && vectorStoreIsEmpty()) {
            log.error("Die Datenbank enthält {} indexierte Dokumente und {} Fakten, aber der VectorStore {} ist leer "
                    + "oder fehlt. Suche und Gedächtnis liefern nichts. Datei wiederherstellen oder Dokumente/Fakten "
                    + "neu anlegen.", indexed, factCount, properties.vectorStoreFile());
        }
    }

    /** Ein leerer SimpleVectorStore wird als {@code {}} gespeichert. */
    private boolean vectorStoreIsEmpty() {
        File file = new File(properties.vectorStoreFile());
        return !file.isFile() || file.length() <= 2;
    }
}
