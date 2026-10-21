package de.grauk.jarvis.knowledge;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Hochgeladenes Dokument (Tabelle {@code knowledge_document}); die Chunks liegen im VectorStore. */
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument {

    public enum Status { PENDING, INDEXED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private int chunks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "status_message", length = 1000)
    private String statusMessage;

    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected KnowledgeDocument() {
    }

    public KnowledgeDocument(String filename) {
        this.filename = filename;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public String getFilename() { return filename; }
    public int getChunks() { return chunks; }
    public Status getStatus() { return status; }
    public String getStatusMessage() { return statusMessage; }
    public LocalDateTime getIndexedAt() { return indexedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    void markIndexed(int chunkCount) {
        this.status = Status.INDEXED;
        this.chunks = chunkCount;
        this.statusMessage = null;
        this.indexedAt = LocalDateTime.now();
    }

    void markFailed(String message) {
        this.status = Status.FAILED;
        this.chunks = 0;
        this.indexedAt = null;
        this.statusMessage = message != null && message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
