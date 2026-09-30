package de.grauk.jarvis.knowledge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Upload → Tika → TokenTextSplitter → VectorStore. DB-Zeile und Chunks bleiben immer konsistent. */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "md", "txt");
    static final int DEFAULT_MAX_TEXT_CHARS = 2_000_000;
    static final int DEFAULT_MAX_CHUNKS = 2000;

    /** Nicht final, damit Tests die Grenzen billig erreichen können. */
    int maxTextChars = DEFAULT_MAX_TEXT_CHARS;
    int maxChunks = DEFAULT_MAX_CHUNKS;

    private final KnowledgeDocumentRepository repository;
    private final VectorStore vectorStore;
    private final VectorStoreConfig vectorStoreConfig;

    public DocumentIngestionService(KnowledgeDocumentRepository repository, VectorStore vectorStore,
            VectorStoreConfig vectorStoreConfig) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.vectorStoreConfig = vectorStoreConfig;
    }

    /** Status PENDING → INDEXED | FAILED; Verarbeitungsfehler werden nicht geworfen, sondern als FAILED geliefert. */
    public KnowledgeDocument ingest(MultipartFile file) {
        String filename = baseFilename(file == null ? null : file.getOriginalFilename());
        KnowledgeDocument doc = repository.save(new KnowledgeDocument(filename));
        try {
            checkExtension(filename);
            byte[] bytes = file == null ? new byte[0] : file.getBytes();
            if (bytes.length == 0) {
                throw new KnowledgeException("Die Datei ist leer.");
            }
            List<Document> chunks = split(read(bytes, filename), doc.getId(), filename);
            if (chunks.isEmpty()) {
                throw new KnowledgeException("Aus der Datei konnte kein Text extrahiert werden.");
            }
            if (chunks.size() > maxChunks) {
                throw new KnowledgeException("Das Dokument ist zu groß (mehr als " + maxChunks + " Abschnitte).");
            }
            vectorStore.add(chunks);
            vectorStoreConfig.persist();
            doc.markIndexed(chunks.size());
        } catch (Exception e) {
            log.error("Indexierung von '{}' (id={}) fehlgeschlagen", filename, doc.getId(), e);
            boolean leftover = removeChunks(doc.getId(), e);
            doc.markFailed(readableMessage(e) + (leftover
                    ? " Beim Aufräumen sind Fehler aufgetreten, Reste im Vektorspeicher können zurückbleiben." : ""));
        }
        return repository.save(doc);
    }

    /** Entfernt alle Chunks des Dokuments und die DB-Zeile. */
    public void delete(long documentId) {
        vectorStore.delete(documentFilter(documentId));
        vectorStoreConfig.persist();
        repository.deleteById(documentId);
    }

    public List<KnowledgeDocument> list() {
        return repository.findAllByOrderByCreatedAtDescIdDesc();
    }

    private List<Document> read(byte[] bytes, String filename) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        try {
            // Schreiblimit im Content-Handler bricht die Extraktion (z. B. Zip-Bombe) früh ab
            return new TikaDocumentReader(resource, new BodyContentHandler(maxTextChars),
                    ExtractedTextFormatter.defaults()).get();
        } catch (RuntimeException e) {
            if (isWriteLimit(e)) {
                throw new KnowledgeException("Das Dokument enthält zu viel Text (mehr als " + maxTextChars
                        + " Zeichen).", e);
            }
            throw e;
        }
    }

    private static boolean isWriteLimit(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof WriteLimitReachedException) {
                return true;
            }
        }
        return false;
    }

    private static void checkExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        String ext = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new KnowledgeException("Dateityp nicht unterstützt (erlaubt: pdf, docx, md, txt).");
        }
    }

    private List<Document> split(List<Document> pages, long documentId, String filename) {
        List<Document> tagged = new ArrayList<>();
        for (Document page : pages) {
            if (page.getText() == null || page.getText().isBlank()) {
                continue;
            }
            Map<String, Object> metadata = new HashMap<>(page.getMetadata());
            // ids als String: überleben den JSON-Round-Trip des SimpleVectorStore typstabil
            metadata.put("type", "document");
            metadata.put("documentId", String.valueOf(documentId));
            metadata.put("filename", filename);
            tagged.add(new Document(page.getText(), metadata));
        }
        return tagged.isEmpty() ? tagged : new TokenTextSplitter().apply(tagged);
    }

    /** Aufräumen nach Fehlschlag; wirft nicht, hängt einen Fehler als suppressed an; true = Reste möglich. */
    private boolean removeChunks(Long documentId, Exception original) {
        try {
            vectorStore.delete(documentFilter(documentId));
            vectorStoreConfig.persist();
            return false;
        } catch (RuntimeException e) {
            log.error("Aufräumen der Chunks von Dokument {} fehlgeschlagen", documentId, e);
            original.addSuppressed(e);
            return true;
        }
    }

    private static Filter.Expression documentFilter(long documentId) {
        return new FilterExpressionBuilder().eq("documentId", String.valueOf(documentId)).build();
    }

    private static String readableMessage(Exception e) {
        if (e instanceof KnowledgeException) {
            return e.getMessage();
        }
        if (e instanceof IOException) {
            return "Die Datei konnte nicht gelesen werden: " + e.getMessage();
        }
        return "Indexierung fehlgeschlagen (ist der LLM-Server erreichbar?): " + e.getMessage();
    }

    static String baseFilename(String original) {
        if (original == null || original.isBlank()) {
            return "unbenannt";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.equals("..") || name.equals(".")) {
            return "unbenannt";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
