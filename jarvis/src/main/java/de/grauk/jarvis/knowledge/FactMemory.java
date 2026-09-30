package de.grauk.jarvis.knowledge;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/** Fakten merken/auflisten/löschen – immer DB und VectorStore gemeinsam. */
@Service
public class FactMemory {

    static final int MAX_LENGTH = 2000;

    private static final Logger log = LoggerFactory.getLogger(FactMemory.class);

    private final MemoryFactRepository repository;
    private final VectorStore vectorStore;
    private final VectorStoreConfig vectorStoreConfig;
    private final KnowledgeProperties properties;

    public FactMemory(MemoryFactRepository repository, VectorStore vectorStore, VectorStoreConfig vectorStoreConfig,
            KnowledgeProperties properties) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.vectorStoreConfig = vectorStoreConfig;
        this.properties = properties;
    }

    /** Legt DB-Zeile und Vektor an; schlägt das Embedding fehl, bleibt keine Zeile zurück. */
    public MemoryFact remember(String content, @Nullable Long sourceMessageId) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Der Fakt darf nicht leer sein.");
        }
        String text = content.strip();
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Der Fakt ist zu lang (maximal " + MAX_LENGTH + " Zeichen).");
        }
        MemoryFact fact = repository.save(new MemoryFact(text, sourceMessageId));
        try {
            vectorStore.add(List.of(new Document("fact-" + fact.getId(), text,
                    Map.of("type", "fact", "factId", String.valueOf(fact.getId())))));
            vectorStoreConfig.persist();
        } catch (RuntimeException e) {
            log.error("Fakt {} konnte nicht gespeichert werden", fact.getId(), e);
            boolean leftover = false;
            try {
                vectorStore.delete(factFilter(fact.getId()));
                vectorStoreConfig.persist();
            } catch (RuntimeException cleanup) {
                log.error("Aufräumen des Fakt-Vektors {} fehlgeschlagen", fact.getId(), cleanup);
                e.addSuppressed(cleanup);
                leftover = true;
            }
            try {
                repository.deleteById(fact.getId());
            } catch (RuntimeException cleanup) {
                log.error("Aufräumen der Fakt-Zeile {} fehlgeschlagen", fact.getId(), cleanup);
                e.addSuppressed(cleanup);
            }
            throw new KnowledgeException("Der Fakt konnte nicht gemerkt werden (ist der LLM-Server erreichbar?): "
                    + e.getMessage() + (leftover ? " Beim Aufräumen sind Fehler aufgetreten, Reste im Vektorspeicher "
                    + "können zurückbleiben." : ""), e);
        }
        return fact;
    }

    public List<MemoryFact> list() {
        return repository.findAllByOrderByCreatedAtDescIdDesc();
    }

    public void delete(long factId) {
        vectorStore.delete(factFilter(factId));
        vectorStoreConfig.persist();
        repository.deleteById(factId);
    }

    /** Ähnlichkeitssuche über Dokument-Chunks und Fakten, mit dem konfigurierten Schwellwert. */
    public List<Document> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<Document> result = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK > 0 ? topK : properties.topK())
                .similarityThreshold(properties.similarityThreshold())
                .build());
        return result == null ? List.of() : result;
    }

    private static Filter.Expression factFilter(long factId) {
        return new FilterExpressionBuilder().eq("factId", String.valueOf(factId)).build();
    }
}
