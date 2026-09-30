package de.grauk.jarvis.knowledge;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Gemerkter Fakt (Tabelle {@code memory_fact}); zusätzlich im VectorStore mit {@code type=fact}. */
@Entity
@Table(name = "memory_fact")
public class MemoryFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(name = "source_message_id")
    private Long sourceMessageId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected MemoryFact() {
    }

    public MemoryFact(String content, Long sourceMessageId) {
        this.content = content;
        this.sourceMessageId = sourceMessageId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public String getContent() { return content; }
    public Long getSourceMessageId() { return sourceMessageId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
