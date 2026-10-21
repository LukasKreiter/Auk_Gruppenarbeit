package de.grauk.jarvis.conversation;

import java.time.LocalDateTime;

import de.grauk.jarvis.assistant.ChatProvider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Eine Nachricht (Tabelle {@code message}). {@code provider}/{@code model}/{@code llmMs} gibt es nur bei
 * Assistenten-Nachrichten, {@code sttMs} nur bei gesprochenen Nutzernachrichten; {@code ttsMs} trägt der Sprachpfad nach.
 */
@Entity
@Table(name = "message")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(length = 10)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ChatProvider provider;

    @Column(length = 100)
    private String model;

    @Column(name = "stt_ms")
    private Long sttMs;

    @Column(name = "llm_ms")
    private Long llmMs;

    @Column(name = "tts_ms")
    private Long ttsMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Message() {
    }

    public Message(long conversationId, Role role, String content) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public Role getRole() { return role; }
    public String getContent() { return content; }
    public String getLanguage() { return language; }
    void setLanguage(String language) { this.language = language; }
    public ChatProvider getProvider() { return provider; }
    void setProvider(ChatProvider provider) { this.provider = provider; }
    public String getModel() { return model; }
    void setModel(String model) { this.model = model; }
    public Long getSttMs() { return sttMs; }
    void setSttMs(Long sttMs) { this.sttMs = sttMs; }
    public Long getLlmMs() { return llmMs; }
    void setLlmMs(Long llmMs) { this.llmMs = llmMs; }
    public Long getTtsMs() { return ttsMs; }
    public void setTtsMs(Long ttsMs) { this.ttsMs = ttsMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
