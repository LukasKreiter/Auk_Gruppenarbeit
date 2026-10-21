package de.grauk.jarvis.conversation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import de.grauk.jarvis.assistant.AssistantEvent;
import de.grauk.jarvis.assistant.ToolCallRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Konversationen, Nachrichten und Tool-Aufrufe. Die Persistenz der Assistenten-Antwort geschieht aus
 * {@link AssistantEvent.Done}; {@code assistant/} selbst schreibt nichts in diese Tabellen.
 */
@Service
@Transactional
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    public static final String DEFAULT_TITLE = "Neue Unterhaltung";
    static final int TITLE_FROM_MESSAGE_LENGTH = 60;
    static final int MAX_LANGUAGE_LENGTH = 10;

    /** Nachricht samt ihren Tool-Aufrufen (leer bei Nutzernachrichten). */
    public record MessageView(Message message, List<ToolCall> toolCalls) {}

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ToolCallRepository toolCalls;
    private final ChatMemory chatMemory;

    public ConversationService(ConversationRepository conversations, MessageRepository messages,
                               ToolCallRepository toolCalls, ChatMemory chatMemory) {
        this.conversations = conversations;
        this.messages = messages;
        this.toolCalls = toolCalls;
        this.chatMemory = chatMemory;
    }

    /** Legt eine Konversation an; ohne Titel heißt sie {@link #DEFAULT_TITLE} und wird bei der ersten Nutzernachricht benannt. */
    public Conversation create(@Nullable String title) {
        String clean = title == null || title.isBlank() ? DEFAULT_TITLE : validTitle(title);
        return conversations.save(new Conversation(clean));
    }

    /** @throws IllegalArgumentException bei leerem oder zu langem Titel; {@link ConversationNotFoundException} */
    public Conversation rename(long id, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Der Titel darf nicht leer sein");
        }
        Conversation conversation = get(id);
        conversation.setTitle(validTitle(title));
        return conversation;
    }

    /**
     * Entfernt die Konversation (Nachrichten und Tool-Aufrufe per DB-Cascade) und den Spring-AI-Chat-Verlauf
     * ({@code SPRING_AI_CHAT_MEMORY}) dieser Id.
     */
    public void delete(long id) {
        Conversation conversation = get(id);
        conversations.delete(conversation);
        conversations.flush();
        String memoryId = String.valueOf(id);
        // Chat-Verlauf erst nach erfolgreichem Commit löschen; bei Rollback bleibt er zur Konversation passend erhalten
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    clearChatMemory(memoryId);
                }
            });
        } else {
            clearChatMemory(memoryId);
        }
    }

    private void clearChatMemory(String memoryId) {
        try {
            chatMemory.clear(memoryId);
        } catch (RuntimeException e) {
            log.error("Konversation {} ist gelöscht, aber ihr Chat-Verlauf (SPRING_AI_CHAT_MEMORY) konnte nicht "
                    + "entfernt werden – Reste bleiben bestehen", memoryId, e);
        }
    }

    @Transactional(readOnly = true)
    public Conversation get(long id) {
        return conversations.findById(id).orElseThrow(() -> new ConversationNotFoundException(id));
    }

    /** Neueste Aktivität zuerst. */
    @Transactional(readOnly = true)
    public List<Conversation> list() {
        return conversations.findAllByOrderByUpdatedAtDescIdDesc();
    }

    @Transactional(readOnly = true)
    public List<MessageView> messages(long conversationId) {
        get(conversationId);
        List<Message> list = messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
        return withToolCalls(list);
    }

    /**
     * Speichert die Nutzernachricht. Ist es die erste Nachricht einer noch unbenannten Konversation, wird der
     * Titel aus dem Text abgeleitet (gekürzt, kein LLM-Aufruf).
     */
    public Message saveUserMessage(long conversationId, String text, @Nullable String language, @Nullable Long sttMs) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Der Text darf nicht leer sein");
        }
        Conversation conversation = get(conversationId);
        boolean first = messages.countByConversationId(conversationId) == 0;
        Message message = new Message(conversationId, Role.USER, text);
        message.setLanguage(cleanLanguage(language));
        if (sttMs != null && sttMs < 0) {
            throw new IllegalArgumentException("sttMs darf nicht negativ sein");
        }
        message.setSttMs(sttMs);
        Message saved = messages.save(message);
        if (first && DEFAULT_TITLE.equals(conversation.getTitle())) {
            conversation.setTitle(deriveTitle(text));
        }
        conversation.touch();
        return saved;
    }

    /** Speichert die Antwort samt Provider, Modell, {@code llm_ms} und {@code tool_call}-Zeilen aus dem Done-Event. */
    public MessageView saveAssistantMessage(long conversationId, AssistantEvent.Done done) {
        Conversation conversation = get(conversationId);
        Message message = new Message(conversationId, Role.ASSISTANT, done.fullText());
        message.setProvider(done.provider());
        message.setModel(done.model());
        message.setLlmMs(done.llmMillis());
        // Sprache der Antwort = Sprache der letzten Nutzernachricht (für die Stimmenwahl)
        messages.findFirstByConversationIdAndRoleOrderByIdDesc(conversationId, Role.USER)
                .ifPresent(user -> message.setLanguage(user.getLanguage()));
        Message saved = messages.save(message);
        List<ToolCall> savedCalls = toolCalls.saveAll(done.toolCalls().stream()
                .map(record -> toEntity(saved.getId(), record)).toList());
        conversation.touch();
        log.debug("Antwort {} in Konversation {} gespeichert ({} Tool-Aufrufe)", saved.getId(), conversationId,
                savedCalls.size());
        return new MessageView(saved, savedCalls);
    }

    /** Trägt die TTS-Dauer nach, sobald die Sprachausgabe fertig ist. */
    public void recordTtsMillis(long messageId, long ttsMs) {
        messages.findById(messageId).ifPresentOrElse(message -> message.setTtsMs(ttsMs),
                () -> log.warn("TTS-Dauer für unbekannte Nachricht {} verworfen", messageId));
    }

    /** Titel aus der ersten Nutzernachricht: Whitespace zusammengefasst, an einer Wortgrenze gekürzt. */
    public static String deriveTitle(String text) {
        String normalized = text.strip().replaceAll("\\s+", " ");
        if (normalized.length() <= TITLE_FROM_MESSAGE_LENGTH) {
            return normalized.isEmpty() ? DEFAULT_TITLE : normalized;
        }
        int cut = normalized.lastIndexOf(' ', TITLE_FROM_MESSAGE_LENGTH);
        if (cut < TITLE_FROM_MESSAGE_LENGTH / 2) {
            cut = TITLE_FROM_MESSAGE_LENGTH;
        }
        return normalized.substring(0, cut).stripTrailing() + "…";
    }

    private List<MessageView> withToolCalls(List<Message> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ToolCall>> byMessage = toolCalls
                .findByMessageIdInOrderByIdAsc(list.stream().map(Message::getId).toList()).stream()
                .collect(Collectors.groupingBy(ToolCall::getMessageId));
        return list.stream().map(m -> new MessageView(m, byMessage.getOrDefault(m.getId(), List.of()))).toList();
    }

    private static ToolCall toEntity(long messageId, ToolCallRecord record) {
        return new ToolCall(messageId, record.toolName(), record.argumentsJson(), record.result());
    }

    private static String validTitle(String title) {
        String clean = title.strip().replaceAll("\\s+", " ");
        if (clean.length() > Conversation.MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Der Titel ist länger als " + Conversation.MAX_TITLE_LENGTH + " Zeichen");
        }
        return clean;
    }

    private static String cleanLanguage(@Nullable String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String clean = language.strip();
        if (clean.length() > MAX_LANGUAGE_LENGTH) {
            throw new IllegalArgumentException("Ungültige Sprache: " + clean);
        }
        return clean;
    }
}
