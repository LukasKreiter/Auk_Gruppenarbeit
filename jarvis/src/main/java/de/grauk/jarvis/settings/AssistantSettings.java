package de.grauk.jarvis.settings;

import java.time.LocalDateTime;

import de.grauk.jarvis.assistant.ChatProvider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** Einstellungen des Assistenten (Tabelle {@code assistant_settings}); Singleton-Zeile mit {@link #SINGLETON_ID}. */
@Entity
@Table(name = "assistant_settings")
public class AssistantSettings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatProvider provider;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "system_prompt", nullable = false, length = 4000)
    private String systemPrompt;

    @Column(nullable = false)
    private double temperature;

    @Column(name = "think_mode", nullable = false)
    private boolean thinkMode;

    @Column(name = "voice_de", length = 100)
    private String voiceDe;

    @Column(name = "voice_en", length = 100)
    private String voiceEn;

    @Column(name = "tts_enabled", nullable = false)
    private boolean ttsEnabled = true;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AssistantSettings() {
    }

    public AssistantSettings(ChatProvider provider, String model, String systemPrompt, double temperature,
                             boolean thinkMode, String voiceDe, String voiceEn, boolean ttsEnabled) {
        this.provider = provider;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.temperature = temperature;
        this.thinkMode = thinkMode;
        this.voiceDe = voiceDe;
        this.voiceEn = voiceEn;
        this.ttsEnabled = ttsEnabled;
    }

    /** Übernimmt alle änderbaren Felder; {@code id} und {@code updatedAt} bleiben. */
    public void copyFrom(AssistantSettings other) {
        this.provider = other.provider;
        this.model = other.model;
        this.systemPrompt = other.systemPrompt;
        this.temperature = other.temperature;
        this.thinkMode = other.thinkMode;
        this.voiceDe = other.voiceDe;
        this.voiceEn = other.voiceEn;
        this.ttsEnabled = other.ttsEnabled;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public ChatProvider getProvider() { return provider; }
    public void setProvider(ChatProvider provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public boolean isThinkMode() { return thinkMode; }
    public void setThinkMode(boolean thinkMode) { this.thinkMode = thinkMode; }
    public String getVoiceDe() { return voiceDe; }
    public void setVoiceDe(String voiceDe) { this.voiceDe = voiceDe; }
    public String getVoiceEn() { return voiceEn; }
    public void setVoiceEn(String voiceEn) { this.voiceEn = voiceEn; }
    public boolean isTtsEnabled() { return ttsEnabled; }
    public void setTtsEnabled(boolean ttsEnabled) { this.ttsEnabled = ttsEnabled; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
