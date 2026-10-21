package de.grauk.jarvis.web;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.conversation.Conversation;
import de.grauk.jarvis.conversation.ConversationService.MessageView;
import de.grauk.jarvis.conversation.Message;
import de.grauk.jarvis.conversation.Role;
import de.grauk.jarvis.conversation.ToolCall;
import de.grauk.jarvis.knowledge.KnowledgeDocument;
import de.grauk.jarvis.knowledge.MemoryFact;
import de.grauk.jarvis.settings.AssistantSettings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Ein-/Ausgabe-Typen der JSON-API und der Fragmente. Entities werden bewusst nicht direkt ausgeliefert.
 * Alle Zeitstempel sind lokale Zeit ({@code LocalDateTime}, ISO-8601).
 */
public final class Dtos {

    public static final int MAX_TEXT_LENGTH = 8000;

    private Dtos() {
    }

    // ---- Ausgabe ----

    public record ConversationDto(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static ConversationDto from(Conversation c) {
            return new ConversationDto(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    public record ToolCallDto(Long id, String toolName, String arguments, String result, LocalDateTime createdAt) {
        public static ToolCallDto from(ToolCall t) {
            return new ToolCallDto(t.getId(), t.getToolName(), t.getArguments(), t.getResult(), t.getCreatedAt());
        }
    }

    public record MessageDto(Long id, Long conversationId, Role role, String content, String language,
                             ChatProvider provider, String model, Long sttMs, Long llmMs, Long ttsMs,
                             LocalDateTime createdAt, List<ToolCallDto> toolCalls) {
        public static MessageDto from(MessageView view) {
            Message m = view.message();
            return new MessageDto(m.getId(), m.getConversationId(), m.getRole(), m.getContent(), m.getLanguage(),
                    m.getProvider(), m.getModel(), m.getSttMs(), m.getLlmMs(), m.getTtsMs(), m.getCreatedAt(),
                    view.toolCalls().stream().map(ToolCallDto::from).toList());
        }
    }

    public record DocumentDto(Long id, String filename, int chunks, KnowledgeDocument.Status status,
                              String statusMessage, LocalDateTime indexedAt, LocalDateTime createdAt) {
        public static DocumentDto from(KnowledgeDocument d) {
            return new DocumentDto(d.getId(), d.getFilename(), d.getChunks(), d.getStatus(), d.getStatusMessage(),
                    d.getIndexedAt(), d.getCreatedAt());
        }
    }

    public record FactDto(Long id, String content, Long sourceMessageId, LocalDateTime createdAt) {
        public static FactDto from(MemoryFact f) {
            return new FactDto(f.getId(), f.getContent(), f.getSourceMessageId(), f.getCreatedAt());
        }
    }

    public record SettingsDto(ChatProvider provider, String model, String systemPrompt, double temperature,
                              boolean thinkMode, String voiceDe, String voiceEn, boolean ttsEnabled,
                              LocalDateTime updatedAt) {
        public static SettingsDto from(AssistantSettings s) {
            return new SettingsDto(s.getProvider(), s.getModel(), s.getSystemPrompt(), s.getTemperature(),
                    s.isThinkMode(), s.getVoiceDe(), s.getVoiceEn(), s.isTtsEnabled(), s.getUpdatedAt());
        }
    }

    /**
     * Einstellungen plus alles, was die Einstellungsseite braucht.
     *
     * @param available Provider → erreichbar bzw. (Claude) Key vorhanden
     * @param models    Provider → wählbare Modelle (Ollama leer, wenn der Server nicht erreichbar ist)
     */
    public record SettingsView(SettingsDto settings, Map<ChatProvider, Boolean> available,
                               Map<ChatProvider, List<String>> models, String defaultSystemPrompt) {
    }

    // ---- Eingabe ----

    public record CreateConversationRequest(@Size(max = Conversation.MAX_TITLE_LENGTH) String title) {
    }

    public record RenameRequest(@NotBlank @Size(max = Conversation.MAX_TITLE_LENGTH) String title) {
    }

    /** {@code language}: z. B. "de"/"en"; {@code sttMs}: Dauer der Spracherkennung, falls die Eingabe gesprochen war. */
    public record ChatRequest(@NotBlank @Size(max = MAX_TEXT_LENGTH) String text,
                              @Size(max = 10) String language,
                              @PositiveOrZero Long sttMs) {
    }

    public record ToolConfirmation(@NotNull Boolean approved) {
    }

    public record ToolUpdateRequest(@NotNull Boolean enabled, @NotNull Boolean requiresConfirmation) {
    }

    /**
     * Teil-Update: {@code null} lässt den Wert unverändert. Bei {@code voiceDe}/{@code voiceEn} setzt ein leerer
     * String die Stimme auf "Standard des voice-service" zurück.
     */
    public record SettingsRequest(ChatProvider provider, String model, String systemPrompt, Double temperature,
                                  Boolean thinkMode, String voiceDe, String voiceEn, Boolean ttsEnabled) {
    }
}
