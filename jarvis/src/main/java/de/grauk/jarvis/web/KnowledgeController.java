package de.grauk.jarvis.web;

import java.net.URI;
import java.util.List;

import de.grauk.jarvis.knowledge.DocumentIngestionService;
import de.grauk.jarvis.knowledge.FactMemory;
import de.grauk.jarvis.knowledge.KnowledgeDocument;
import de.grauk.jarvis.web.Dtos.DocumentDto;
import de.grauk.jarvis.web.Dtos.FactDto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Wissen für RAG: Dokumente (Upload/Liste/Löschen) und gemerkte Fakten (Liste/Löschen). */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final DocumentIngestionService documents;
    private final FactMemory facts;

    public KnowledgeController(DocumentIngestionService documents, FactMemory facts) {
        this.documents = documents;
        this.facts = facts;
    }

    @GetMapping("/documents")
    public List<DocumentDto> listDocuments() {
        return documents.list().stream().map(DocumentDto::from).toList();
    }

    /**
     * Multipart-Feld {@code file}. Antwort 201 mit dem Ergebnis; schlägt die Indexierung fehl, steht das im
     * Feld {@code status} ({@code FAILED}) und {@code statusMessage} – nicht im HTTP-Status.
     */
    @PostMapping("/documents")
    public ResponseEntity<DocumentDto> upload(@RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Die Datei ist leer");
        }
        DocumentDto created = DocumentDto.from(documents.ingest(file));
        if (created.status() == KnowledgeDocument.Status.FAILED) {
            log.warn("Indexierung von '{}' fehlgeschlagen (Antwort bleibt 201): {}", created.filename(),
                    created.statusMessage());
        }
        return ResponseEntity.created(URI.create("/api/knowledge/documents/" + created.id())).body(created);
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable long id) {
        if (documents.list().stream().map(KnowledgeDocument::getId).noneMatch(existing -> existing == id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dokument nicht gefunden");
        }
        documents.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/facts")
    public List<FactDto> listFacts() {
        return facts.list().stream().map(FactDto::from).toList();
    }

    @DeleteMapping("/facts/{id}")
    public ResponseEntity<Void> deleteFact(@PathVariable long id) {
        if (facts.list().stream().noneMatch(fact -> fact.getId() == id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fakt nicht gefunden");
        }
        facts.delete(id);
        return ResponseEntity.noContent().build();
    }
}
