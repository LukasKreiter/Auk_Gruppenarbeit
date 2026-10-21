package de.grauk.jarvis.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.grauk.jarvis.assistant.AssistantEvent;
import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ToolCallRecord;
import de.grauk.jarvis.conversation.ConversationService.MessageView;
import de.grauk.jarvis.tools.ToolCallStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;

/**
 * Läuft gegen H2 mit den echten Flyway-Migrationen (V1-V3) und {@code ddl-auto=validate}: schlägt fehl,
 * wenn Entities und Schema nicht zusammenpassen. Der Chat-Verlauf nutzt die echte Tabelle SPRING_AI_CHAT_MEMORY.
 */
@DataJpaTest
@Import({ConversationService.class, ConversationServiceTest.Config.class})
class ConversationServiceTest {

    @TestConfiguration
    static class Config {
        @Bean
        ChatMemory chatMemory(JdbcTemplate jdbcTemplate) {
            return MessageWindowChatMemory.builder()
                    .chatMemoryRepository(JdbcChatMemoryRepository.builder().jdbcTemplate(jdbcTemplate).build())
                    .maxMessages(20).build();
        }
    }

    @Autowired ConversationService service;
    @Autowired ChatMemory chatMemory;
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;
    @Autowired ToolCallRepository toolCalls;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * H2 2.4.240 wertet CHECK-Constraints nicht mehr aus, sobald die Verbindung geschlossen ist, die sie angelegt hat
     * (hier: die Flyway-Verbindung des nicht gepoolten Test-DataSource). Für den Chat-Verlauf-Test wird die Tabelle
     * deshalb ohne den CHECK aus V1 neu angelegt; die Spalten sind sonst identisch.
     */
    @BeforeEach
    void recreateChatMemoryTableWithoutCheck() {
        jdbcTemplate.execute("DROP TABLE SPRING_AI_CHAT_MEMORY");
        jdbcTemplate.execute("CREATE TABLE SPRING_AI_CHAT_MEMORY (conversation_id VARCHAR(36) NOT NULL, "
                + "content LONGVARCHAR NOT NULL, type VARCHAR(10) NOT NULL, "
                + "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, sequence_id BIGINT NOT NULL)");
    }

    @Test
    void createsWithDefaultOrGivenTitle() {
        assertThat(service.create(null).getTitle()).isEqualTo(ConversationService.DEFAULT_TITLE);
        assertThat(service.create("  ").getTitle()).isEqualTo(ConversationService.DEFAULT_TITLE);
        assertThat(service.create("  Mein   Thema ").getTitle()).isEqualTo("Mein Thema");
        assertThatThrownBy(() -> service.create("x".repeat(201))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstUserMessageNamesConversation() {
        Conversation c = service.create(null);

        Message first = service.saveUserMessage(c.getId(), "Wie wird das Wetter morgen in Wien?", "de", 120L);
        service.saveUserMessage(c.getId(), "Und übermorgen?", "de", null);

        assertThat(service.get(c.getId()).getTitle()).isEqualTo("Wie wird das Wetter morgen in Wien?");
        assertThat(first.getRole()).isEqualTo(Role.USER);
        assertThat(first.getLanguage()).isEqualTo("de");
        assertThat(first.getSttMs()).isEqualTo(120L);
    }

    @Test
    void manualTitleIsNotOverwritten() {
        Conversation c = service.create("Eigener Titel");

        service.saveUserMessage(c.getId(), "Hallo", null, null);

        assertThat(service.get(c.getId()).getTitle()).isEqualTo("Eigener Titel");
    }

    @Test
    void deriveTitleShortensAtWordBoundary() {
        String title = ConversationService.deriveTitle(
                "Erkläre mir bitte ausführlich wie ein Verbrennungsmotor funktioniert und warum er so laut ist");

        assertThat(title).hasSizeLessThanOrEqualTo(61).endsWith("…").doesNotContain("  ");
        assertThat(title).isEqualTo("Erkläre mir bitte ausführlich wie ein Verbrennungsmotor…");
        assertThat(ConversationService.deriveTitle("a".repeat(100))).isEqualTo("a".repeat(60) + "…");
        assertThat(ConversationService.deriveTitle("  kurz \n text ")).isEqualTo("kurz text");
    }

    @Test
    void renameValidates() {
        Conversation c = service.create(null);

        assertThat(service.rename(c.getId(), " Neu ").getTitle()).isEqualTo("Neu");
        assertThatThrownBy(() -> service.rename(c.getId(), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename(9999, "x")).isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void rejectsBlankUserTextAndUnknownConversation() {
        Conversation c = service.create(null);

        assertThatThrownBy(() -> service.saveUserMessage(c.getId(), " ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveUserMessage(9999, "Hallo", null, null))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void listIsSortedByLatestActivity() throws InterruptedException {
        Conversation a = service.create("A");
        Thread.sleep(5);
        Conversation b = service.create("B");
        assertThat(service.list()).extracting(Conversation::getId).containsSubsequence(b.getId(), a.getId());

        Thread.sleep(5);
        service.saveUserMessage(a.getId(), "nachricht", null, null);

        assertThat(service.list()).extracting(Conversation::getId).containsSubsequence(a.getId(), b.getId());
    }

    @Test
    void savesAssistantMessageWithToolCalls() {
        Conversation c = service.create(null);
        service.saveUserMessage(c.getId(), "Wie spät ist es?", "en", null);
        var done = new AssistantEvent.Done("Es ist 12 Uhr.", ChatProvider.OLLAMA, "qwen3:8b", 842,
                List.of(new ToolCallRecord("getCurrentDateTime", "{}", "12:00"),
                        new ToolCallRecord("setTimer", "{\"minutes\":5}", "ok")));

        MessageView saved = service.saveAssistantMessage(c.getId(), done);

        Message m = saved.message();
        assertThat(m.getId()).isNotNull();
        assertThat(m.getRole()).isEqualTo(Role.ASSISTANT);
        assertThat(m.getProvider()).isEqualTo(ChatProvider.OLLAMA);
        assertThat(m.getModel()).isEqualTo("qwen3:8b");
        assertThat(m.getLlmMs()).isEqualTo(842L);
        assertThat(m.getLanguage()).isEqualTo("en");
        assertThat(saved.toolCalls()).extracting(ToolCall::getToolName).containsExactly("getCurrentDateTime", "setTimer");

        List<MessageView> loaded = service.messages(c.getId());
        assertThat(loaded).extracting(v -> v.message().getRole()).containsExactly(Role.USER, Role.ASSISTANT);
        assertThat(loaded.get(0).toolCalls()).isEmpty();
        assertThat(loaded.get(1).toolCalls()).extracting(ToolCall::getResult).containsExactly("12:00", "ok");
    }

    @Test
    void storesLongContent() {
        Conversation c = service.create(null);
        String longText = "wort ".repeat(5000);

        Message saved = service.saveUserMessage(c.getId(), longText, null, null);
        messages.flush();

        assertThat(messages.findById(saved.getId())).get().extracting(Message::getContent).isEqualTo(longText);
    }

    @Test
    void failedAnswerKeepsToolCallsVisibleInHistory() {
        Conversation c = service.create(null);
        service.saveUserMessage(c.getId(), "Timer 5 Minuten", "de", null);

        MessageView saved = service.saveFailedAnswer(c.getId(), "LLM-Server nicht erreichbar",
                List.of(new ToolCallRecord("setTimer", "{\"minutes\":5}", "Timer gestellt"),
                        new ToolCallRecord("openApplication", "{}", "Fehler bei Tool", ToolCallStatus.FAILED)));

        assertThat(saved.message().getRole()).isEqualTo(Role.ASSISTANT);
        assertThat(saved.message().getContent()).contains("LLM-Server nicht erreichbar");
        assertThat(service.messages(c.getId()).getLast().toolCalls()).extracting(ToolCall::getStatus)
                .containsExactly(ToolCallStatus.OK, ToolCallStatus.FAILED);
    }

    @Test
    void deleteRemovesMessagesToolCallsAndChatMemoryAfterCommit() {
        Conversation c = service.create(null);
        Conversation other = service.create(null);
        service.saveUserMessage(c.getId(), "Hallo", null, null);
        Long answerId = service.saveAssistantMessage(c.getId(), new AssistantEvent.Done("Hi", ChatProvider.OLLAMA, "m", 1,
                List.of(new ToolCallRecord("t", "{}", "r"))))
                .message().getId();
        service.saveUserMessage(other.getId(), "bleibt", null, null);
        chatMemory.add(String.valueOf(c.getId()), new UserMessage("Hallo"));
        chatMemory.add(String.valueOf(other.getId()), new UserMessage("bleibt"));
        toolCalls.flush();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        TestTransaction.start();
        service.delete(c.getId());
        // noch nicht committed: Chat-Verlauf darf nicht angetastet sein
        assertThat(chatMemory.get(String.valueOf(c.getId()))).hasSize(1);
        TestTransaction.flagForCommit();
        TestTransaction.end();

        assertThat(conversations.findById(c.getId())).isEmpty();
        assertThat(messages.countByConversationId(c.getId())).isZero();
        assertThat(toolCalls.findByMessageIdInOrderByIdAsc(List.of(answerId))).isEmpty();
        assertThat(chatMemory.get(String.valueOf(c.getId()))).isEmpty();
        assertThat(chatMemory.get(String.valueOf(other.getId()))).hasSize(1);
        assertThat(messages.countByConversationId(other.getId())).isEqualTo(1);
        assertThatThrownBy(() -> service.delete(c.getId())).isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void deleteKeepsChatMemoryOnRollback() {
        Conversation c = service.create(null);
        chatMemory.add(String.valueOf(c.getId()), new UserMessage("Hallo"));
        TestTransaction.flagForCommit();
        TestTransaction.end();

        TestTransaction.start();
        service.delete(c.getId());
        TestTransaction.flagForRollback();
        TestTransaction.end();

        assertThat(conversations.findById(c.getId())).isPresent();
        assertThat(chatMemory.get(String.valueOf(c.getId()))).hasSize(1);
    }

    @Test
    void recordTtsMillisIgnoresUnknownMessage() {
        Conversation c = service.create(null);
        Message m = service.saveUserMessage(c.getId(), "Hallo", null, null);

        service.recordTtsMillis(m.getId(), 55);
        service.recordTtsMillis(987654321L, 55);

        assertThat(messages.findById(m.getId())).get().extracting(Message::getTtsMs).isEqualTo(55L);
    }
}
