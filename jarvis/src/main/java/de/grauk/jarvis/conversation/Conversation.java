package de.grauk.jarvis.conversation;

<<<<<<< Updated upstream
import org.springframework.web.reactive.function.client.WebClient;
import java.util.ArrayList;
import java.util.List;

public class Conversation {

    private List<Message> messages = new ArrayList<>();
    private AudioRecorder audioRecorder;

    public void startVoiceInput() throws Exception {
        this.audioRecorder = new AudioRecorder();
        this.audioRecorder.startRecording();
    }

    public Message stopVoiceInputAndProcess(WebClient.Builder webClientBuilder) {
        if (this.audioRecorder == null) {
            throw new IllegalStateException("Keine Aufnahme aktiv!");
        }

        // 1. Aufnahme stoppen und RAM-Bytes holen
        byte[] audioBytes = this.audioRecorder.stopRecording();
        this.audioRecorder = null;

        // 2. Message über Python STT erstellen
        Message audioMessage = Message.createFromAudioBytes(audioBytes, webClientBuilder);

        // 3. Zur Conversation hinzufügen
        this.messages.add(audioMessage);

        return audioMessage;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void addMessage(Message message) {
        this.messages.add(message);
    }
}
=======
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Ein Gespräch (Tabelle {@code conversation}). Nachrichten verweisen per {@code conversation_id} darauf. */
@Entity
@Table(name = "conversation")
public class Conversation {

    public static final int MAX_TITLE_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Conversation() {
    }

    public Conversation(String title) {
        this.title = title;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    /** Markiert die Konversation als geändert (sortiert sie in der Liste nach oben). */
    void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
>>>>>>> Stashed changes
