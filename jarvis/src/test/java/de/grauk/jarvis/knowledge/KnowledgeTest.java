package de.grauk.jarvis.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;

@DataJpaTest
@Import({ KnowledgeTest.Config.class, VectorStoreConfig.class, DocumentIngestionService.class, FactMemory.class,
        KnowledgeStartupCheck.class })
class KnowledgeTest {

    static final Path DIR = tempDir();
    static final FakeEmbeddingModel EMBEDDINGS = new FakeEmbeddingModel();

    @TestConfiguration
    static class Config {
        @Bean
        KnowledgeProperties knowledgeProperties() {
            return new KnowledgeProperties(DIR.resolve("store.json").toString(), 0.0, 4);
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return EMBEDDINGS;
        }
    }

    @Autowired VectorStore vectorStore;
    @Autowired VectorStoreConfig vectorStoreConfig;
    @Autowired DocumentIngestionService ingestion;
    @Autowired FactMemory facts;
    @Autowired KnowledgeStartupCheck startupCheck;
    @Autowired KnowledgeDocumentRepository documentRepository;
    @Autowired KnowledgeProperties properties;
    @Autowired MemoryFactRepository factRepository;

    @BeforeEach
    void reset() {
        EMBEDDINGS.failing = false;
        var b = new FilterExpressionBuilder();
        vectorStore.delete(b.or(b.eq("type", "document"), b.eq("type", "fact")).build());
    }

    private List<Document> all(VectorStore store) {
        return store.similaritySearch(SearchRequest.builder().query("alpha").topK(100).build());
    }

    private static MockMultipartFile txt(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }

    @Test
    void ingestIndexesChunksWithMetadata() {
        KnowledgeDocument doc = ingestion.ingest(txt("C:\\evil\\..\\notes.txt", "Alpha beta gamma. Delta epsilon."));

        assertThat(doc.getStatus()).isEqualTo(KnowledgeDocument.Status.INDEXED);
        assertThat(doc.getFilename()).isEqualTo("notes.txt");
        assertThat(doc.getChunks()).isPositive();
        assertThat(doc.getIndexedAt()).isNotNull();
        List<Document> stored = all(vectorStore);
        assertThat(stored).hasSize(doc.getChunks());
        assertThat(stored).allSatisfy(d -> {
            assertThat(d.getMetadata()).containsEntry("type", "document")
                    .containsEntry("documentId", String.valueOf(doc.getId()))
                    .containsEntry("filename", "notes.txt");
        });
        assertThat(ingestion.list()).extracting(KnowledgeDocument::getId).contains(doc.getId());
    }

    @Test
    void embeddingFailureMarksFailedWithoutOrphans() {
        EMBEDDINGS.failing = true;
        KnowledgeDocument doc = ingestion.ingest(txt("a.txt", "Alpha beta gamma."));

        assertThat(doc.getStatus()).isEqualTo(KnowledgeDocument.Status.FAILED);
        assertThat(doc.getStatusMessage()).isNotBlank();
        assertThat(doc.getChunks()).isZero();
        EMBEDDINGS.failing = false;
        assertThat(all(vectorStore)).isEmpty();
    }

    @Test
    void unsupportedExtensionIsFailed() {
        KnowledgeDocument doc = ingestion.ingest(new MockMultipartFile("file", "tool.EXE", "application/octet-stream",
                "abc".getBytes()));
        assertThat(doc.getStatus()).isEqualTo(KnowledgeDocument.Status.FAILED);
        assertThat(doc.getStatusMessage()).contains("nicht unterstützt");
        assertThat(ingestion.ingest(txt("GROSS.TXT", "Alpha beta.")).getStatus())
                .isEqualTo(KnowledgeDocument.Status.INDEXED);
    }

    @Test
    void chunkCapFailsDocument() {
        int old = ingestion.maxChunks;
        ingestion.maxChunks = 0;
        try {
            KnowledgeDocument doc = ingestion.ingest(txt("a.txt", "Alpha beta gamma."));
            assertThat(doc.getStatus()).isEqualTo(KnowledgeDocument.Status.FAILED);
            assertThat(doc.getStatusMessage()).contains("zu groß");
            assertThat(all(vectorStore)).isEmpty();
        } finally {
            ingestion.maxChunks = old;
        }
    }

    @Test
    void textCapFailsDocument() {
        int old = ingestion.maxTextChars;
        ingestion.maxTextChars = 10;
        try {
            KnowledgeDocument doc = ingestion.ingest(txt("a.txt", "Alpha beta gamma delta epsilon zeta eta theta."));
            assertThat(doc.getStatus()).isEqualTo(KnowledgeDocument.Status.FAILED);
            assertThat(doc.getStatusMessage()).contains("zu viel Text");
        } finally {
            ingestion.maxTextChars = old;
        }
    }

    @Test
    void startupMarksPendingDocumentsAsFailed() {
        Long id = documentRepository.save(new KnowledgeDocument("hängt.txt")).getId();

        startupCheck.onReady();

        KnowledgeDocument doc = documentRepository.findById(id).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(KnowledgeDocument.Status.FAILED);
        assertThat(doc.getStatusMessage()).contains("Neustart");
    }

    @Test
    void emptyFileIsFailed() {
        KnowledgeDocument doc = ingestion.ingest(txt("leer.txt", ""));
        assertThat(doc.getStatus()).isEqualTo(KnowledgeDocument.Status.FAILED);
        assertThat(doc.getStatusMessage()).contains("leer");

        KnowledgeDocument blank = ingestion.ingest(txt("blank.txt", "   \n  "));
        assertThat(blank.getStatus()).isEqualTo(KnowledgeDocument.Status.FAILED);
        assertThat(all(vectorStore)).isEmpty();
    }

    @Test
    void deleteDocumentRemovesChunksAndRow() {
        KnowledgeDocument keep = ingestion.ingest(txt("keep.txt", "Alpha keeps."));
        KnowledgeDocument gone = ingestion.ingest(txt("gone.txt", "Beta goes."));

        ingestion.delete(gone.getId());

        assertThat(ingestion.list()).extracting(KnowledgeDocument::getId).containsExactly(keep.getId());
        assertThat(all(vectorStore)).allSatisfy(
                d -> assertThat(d.getMetadata()).containsEntry("documentId", String.valueOf(keep.getId())));
        assertThat(all(vectorStore)).isNotEmpty();
    }

    @Test
    void rememberAndDeleteKeepDbAndStoreConsistent() {
        MemoryFact fact = facts.remember("  Der Hund heisst Rex  ", 7L);

        assertThat(fact.getId()).isNotNull();
        assertThat(fact.getContent()).isEqualTo("Der Hund heisst Rex");
        assertThat(facts.list()).hasSize(1);
        assertThat(facts.search("Hund Rex", 4)).singleElement().satisfies(d -> {
            assertThat(d.getMetadata()).containsEntry("type", "fact")
                    .containsEntry("factId", String.valueOf(fact.getId()));
            assertThat(d.getText()).isEqualTo("Der Hund heisst Rex");
        });

        facts.delete(fact.getId());

        assertThat(facts.list()).isEmpty();
        assertThat(all(vectorStore)).isEmpty();
    }

    @Test
    void failedEmbeddingLeavesNoFactRow() {
        EMBEDDINGS.failing = true;
        assertThatThrownBy(() -> facts.remember("Etwas", null)).isInstanceOf(KnowledgeException.class);
        EMBEDDINGS.failing = false;

        assertThat(factRepository.count()).isZero();
        assertThat(all(vectorStore)).isEmpty();
    }

    @Test
    void rememberValidatesContent() {
        assertThatThrownBy(() -> facts.remember("  ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facts.remember("x".repeat(2001), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(factRepository.count()).isZero();
    }

    @Test
    void persistLoadRoundTripAndDeleteByFilterAfterReload() {
        KnowledgeDocument doc = ingestion.ingest(txt("r.txt", "Alpha beta gamma."));
        MemoryFact fact = facts.remember("Alpha Fakt", null);
        int before = all(vectorStore).size();
        assertThat(before).isEqualTo(doc.getChunks() + 1);

        VectorStore reloaded = new VectorStoreConfig(properties).vectorStore(EMBEDDINGS);
        assertThat(all(reloaded)).hasSize(before);

        // nach dem JSON-Round-Trip muss der Filter auf die String-ids weiterhin greifen
        reloaded.delete(new FilterExpressionBuilder().eq("documentId", String.valueOf(doc.getId())).build());
        List<Document> rest = all(reloaded);
        assertThat(rest).hasSize(1);
        assertThat(rest.get(0).getMetadata()).containsEntry("factId", String.valueOf(fact.getId()));
        reloaded.delete(new FilterExpressionBuilder().eq("factId", String.valueOf(fact.getId())).build());
        assertThat(all(reloaded)).isEmpty();
    }

    @Test
    void corruptStoreFileFailsLoudly() throws IOException {
        Path bad = Files.createTempFile(DIR, "bad", ".json");
        Files.writeString(bad, "{ das ist kein json");
        KnowledgeProperties props = new KnowledgeProperties(bad.toString(), 0.0, 4);

        assertThatThrownBy(() -> new VectorStoreConfig(props).vectorStore(EMBEDDINGS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(bad)).isEqualTo("{ das ist kein json");
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("jarvis-knowledge-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deterministisches Bag-of-Words-Embedding (32 Dimensionen), ohne Ollama. */
    static class FakeEmbeddingModel implements EmbeddingModel {

        volatile boolean failing;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (failing) {
                throw new IllegalStateException("LLM-Server nicht erreichbar");
            }
            List<Embedding> result = new ArrayList<>();
            List<String> inputs = request.getInstructions();
            for (int i = 0; i < inputs.size(); i++) {
                result.add(new Embedding(vector(inputs.get(i)), i));
            }
            return new EmbeddingResponse(result);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public float[] embed(String text) {
            if (failing) {
                throw new IllegalStateException("LLM-Server nicht erreichbar");
            }
            return vector(text);
        }

        @Override
        public int dimensions() {
            return 32;
        }

        private static float[] vector(String text) {
            float[] v = new float[32];
            for (String word : text.toLowerCase().split("\\W+")) {
                if (!word.isEmpty()) {
                    v[Math.floorMod(word.hashCode(), 32)] += 1f;
                }
            }
            v[31] += 0.01f;
            return v;
        }
    }
}
