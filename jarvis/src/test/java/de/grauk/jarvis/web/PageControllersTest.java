package de.grauk.jarvis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ChatProviderRegistry;
import de.grauk.jarvis.conversation.Conversation;
import de.grauk.jarvis.conversation.ConversationNotFoundException;
import de.grauk.jarvis.conversation.ConversationService;
import de.grauk.jarvis.knowledge.DocumentIngestionService;
import de.grauk.jarvis.knowledge.FactMemory;
import de.grauk.jarvis.knowledge.KnowledgeDocument;
import de.grauk.jarvis.knowledge.KnowledgeException;
import de.grauk.jarvis.knowledge.MemoryFact;
import de.grauk.jarvis.assistant.ProviderUnavailableException;
import de.grauk.jarvis.settings.AssistantSettings;
import de.grauk.jarvis.settings.SettingsService;
import de.grauk.jarvis.status.StatusService;
import de.grauk.jarvis.tools.ToolRegistry;
import de.grauk.jarvis.web.Dtos.ConversationDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** Seiten- und Fragment-Controller ohne Template-Rendering: geprüft werden View-Namen und Model-Attribute. */
class PageControllersTest {

    private final ConversationService conversations = mock(ConversationService.class);
    private final SettingsService settingsService = mock(SettingsService.class);
    private final ChatProviderRegistry registry = mock(ChatProviderRegistry.class);
    private final ToolRegistry tools = mock(ToolRegistry.class);
    private final DocumentIngestionService documents = mock(DocumentIngestionService.class);
    private final FactMemory facts = mock(FactMemory.class);
    private final StatusService statusService = mock(StatusService.class);

    private DashboardController dashboard;
    private HTMXFragmentController fragments;
    private final Model model = new ExtendedModelMap();

    private static Conversation conversation(long id, String title) {
        Conversation c = new Conversation(title);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    @BeforeEach
    void setUp() {
        SettingsWebService settings = new SettingsWebService(settingsService, registry);
        ViewModels views = new ViewModels(conversations, settings, tools, documents, facts, statusService);
        dashboard = new DashboardController(views);
        fragments = new HTMXFragmentController(views, conversations, settings, tools, documents, facts);

        when(settingsService.getSettings()).thenReturn(
                new AssistantSettings(ChatProvider.ANTHROPIC, "claude-sonnet-5-5", "P", 0.5, false, null, null, true));
        when(registry.available()).thenReturn(Map.of(ChatProvider.OLLAMA, false, ChatProvider.ANTHROPIC, true));
        when(registry.models(any())).thenReturn(List.of("m"));
        when(conversations.list()).thenReturn(List.of(conversation(1, "Erste")));
        when(tools.all()).thenReturn(List.of(new ToolRegistry.ToolInfo("t", "d", true, false)));
    }

    @Test
    void indexPageHasConversationListAndSettings() {
        assertThat(dashboard.dashboard(model)).isEqualTo("chat");

        assertThat(model.asMap()).containsKeys("conversations", "activeConversationId", "settings", "available",
                "models", "providers", "cloudActive");
        assertThat(model.getAttribute("cloudActive")).isEqualTo(true);
        assertThat(model.getAttribute("activeConversationId")).isNull();
    }

    @Test
    void chatPageAddsMessagesAndRedirectsOnUnknownId() {
        when(conversations.get(1)).thenReturn(conversation(1, "Erste"));
        when(conversations.messages(1)).thenReturn(List.of());

        assertThat(dashboard.chat(1, model, new RedirectAttributesModelMap())).isEqualTo("chat");
        assertThat(model.asMap()).containsKeys("activeConversation", "messages");
        assertThat(model.getAttribute("activeConversationId")).isEqualTo(1L);

        when(conversations.get(2)).thenThrow(new ConversationNotFoundException(2));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        assertThat(dashboard.chat(2, new ExtendedModelMap(), redirect)).isEqualTo("redirect:/");
        assertThat(redirect.getFlashAttributes().get("error")).isEqualTo("Unterhaltung nicht gefunden");
    }

    @Test
    void otherPagesFillTheirAttributes() {
        assertThat(dashboard.tools(model)).isEqualTo("tools");
        assertThat(model.asMap()).containsKey("tools");

        Model knowledge = new ExtendedModelMap();
        assertThat(dashboard.knowledge(knowledge)).isEqualTo("knowledge");
        assertThat(knowledge.asMap()).containsKeys("documents", "facts");

        Model settingsPage = new ExtendedModelMap();
        assertThat(dashboard.settings(settingsPage)).isEqualTo("settings");
        assertThat(settingsPage.asMap()).containsKeys("settings", "available", "models", "providers", "defaultSystemPrompt");
    }

    @Test
    void dtoRecordPropertiesAreReadableBySpelAsPlainProperties() {
        ConversationDto dto = new ConversationDto(1L, "Titel", LocalDateTime.now(), LocalDateTime.now());

        Object title = new SpelExpressionParser().parseExpression("title").getValue(dto);

        assertThat(title).isEqualTo("Titel");
    }

    @Test
    void conversationFragmentsReturnFragmentNamesAndUpdatedList() {
        when(conversations.create(null)).thenReturn(conversation(5, "Neue Unterhaltung"));

        assertThat(fragments.createConversation(model)).isEqualTo("fragments/conversation-list :: list");
        assertThat(model.getAttribute("activeConversationId")).isEqualTo(5L);

        Model afterDelete = new ExtendedModelMap();
        assertThat(fragments.deleteConversation(1, 1L, afterDelete)).isEqualTo("fragments/conversation-list :: list");
        verify(conversations).delete(1);
        assertThat(afterDelete.getAttribute("activeConversationId")).isNull();
    }

    @Test
    void renameErrorIsReportedAsModelAttributeWith200Semantics() {
        when(conversations.rename(1, " ")).thenThrow(new IllegalArgumentException("Der Titel darf nicht leer sein"));

        String view = fragments.renameConversation(1, " ", null, model);

        assertThat(view).isEqualTo("fragments/conversation-list :: list");
        assertThat(model.getAttribute("error")).isEqualTo("Der Titel darf nicht leer sein");
        assertThat(model.getAttribute("conversations")).isNotNull();
    }

    @Test
    void settingsFormSavedAndError() {
        when(settingsService.update(any(AssistantSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        String view = fragments.saveSettings(ChatProvider.OLLAMA, "qwen3:8b", "Prompt", 0.7, false, "", "", true, model);
        assertThat(view).isEqualTo("fragments/settings-form :: form");
        assertThat(model.getAttribute("saved")).isEqualTo(true);

        when(settingsService.update(any(AssistantSettings.class))).thenThrow(new IllegalArgumentException("Modell fehlt"));
        Model failed = new ExtendedModelMap();
        fragments.saveSettings(ChatProvider.OLLAMA, "", "Prompt", 0.7, false, "", "", true, failed);
        assertThat(failed.getAttribute("error")).isEqualTo("Modell fehlt");
        assertThat(failed.getAttribute("saved")).isNull();
    }

    @Test
    void toolAndKnowledgeFragments() {
        assertThat(fragments.updateTool("t", true, false, model)).isEqualTo("fragments/tool-list :: list");
        verify(tools).update("t", true, false);

        Model docs = new ExtendedModelMap();
        assertThat(fragments.uploadDocument(new MockMultipartFile("file", "a.txt", "text/plain", new byte[0]), docs))
                .isEqualTo("fragments/document-list :: list");
        assertThat(docs.getAttribute("error")).isEqualTo("Die Datei ist leer");
        assertThat(docs.asMap()).containsKey("documents");

        MemoryFact fact = mock(MemoryFact.class);
        when(fact.getId()).thenReturn(3L);
        when(facts.list()).thenReturn(List.of(fact));
        Model factModel = new ExtendedModelMap();
        assertThat(fragments.deleteFact(3, factModel)).isEqualTo("fragments/fact-list :: list");
        verify(facts).delete(3);
        assertThat(factModel.getAttribute("error")).isNull();
    }

    private static KnowledgeDocument document(long id, KnowledgeDocument.Status status, String message) {
        KnowledgeDocument d = mock(KnowledgeDocument.class);
        when(d.getId()).thenReturn(id);
        when(d.getFilename()).thenReturn("a.txt");
        when(d.getStatus()).thenReturn(status);
        when(d.getStatusMessage()).thenReturn(message);
        return d;
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "a.txt", "text/plain", new byte[] {1});
    }

    @Test
    void renameAndDeleteConversationUnexpectedErrorsBecomeErrorAttribute() {
        when(conversations.rename(1, "x")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        doThrow(new ConversationNotFoundException(9)).when(conversations).delete(9);
        doThrow(new IllegalStateException("kaputt")).when(conversations).delete(8);

        Model renamed = new ExtendedModelMap();
        assertThat(fragments.renameConversation(1, "x", null, renamed)).isEqualTo("fragments/conversation-list :: list");
        Model unknown = new ExtendedModelMap();
        fragments.deleteConversation(9, null, unknown);
        Model broken = new ExtendedModelMap();
        fragments.deleteConversation(8, null, broken);

        assertThat(renamed.getAttribute("error")).isEqualTo("Vorgang fehlgeschlagen, Details im Log");
        assertThat(renamed.getAttribute("conversations")).isNotNull();
        assertThat(unknown.getAttribute("error")).isNotNull();
        assertThat(broken.getAttribute("error")).isEqualTo("Vorgang fehlgeschlagen, Details im Log");
    }

    @Test
    void settingsAndToolUnexpectedErrorsBecomeErrorAttribute() {
        when(settingsService.update(any(AssistantSettings.class)))
                .thenThrow(new ProviderUnavailableException(ChatProvider.OLLAMA, "Ollama nicht erreichbar"));
        doThrow(new IllegalStateException("kaputt")).when(tools).update("t", true, false);
        doThrow(new IllegalArgumentException("Unbekanntes Tool")).when(tools).update("x", true, false);

        Model settingsModel = new ExtendedModelMap();
        assertThat(fragments.saveSettings(ChatProvider.OLLAMA, "m", "P", 0.7, false, "", "", true, settingsModel))
                .isEqualTo("fragments/settings-form :: form");
        Model broken = new ExtendedModelMap();
        assertThat(fragments.updateTool("t", true, false, broken)).isEqualTo("fragments/tool-list :: list");
        Model unknown = new ExtendedModelMap();
        fragments.updateTool("x", true, false, unknown);

        assertThat(settingsModel.getAttribute("error")).isEqualTo("Ollama nicht erreichbar");
        assertThat(settingsModel.getAttribute("saved")).isNull();
        assertThat(broken.getAttribute("error")).isEqualTo("Vorgang fehlgeschlagen, Details im Log");
        assertThat(broken.getAttribute("tools")).isNotNull();
        assertThat(unknown.getAttribute("error")).isEqualTo("Unbekanntes Tool");
    }

    @Test
    void uploadFailedStatusAndExceptionsBecomeErrorAttribute() {
        KnowledgeDocument failedDoc = document(1, KnowledgeDocument.Status.FAILED, "Kein Text gefunden");
        when(documents.ingest(any())).thenReturn(failedDoc);
        Model failed = new ExtendedModelMap();
        assertThat(fragments.uploadDocument(file(), failed)).isEqualTo("fragments/document-list :: list");
        assertThat(failed.getAttribute("error")).isEqualTo("Kein Text gefunden");
        assertThat(failed.getAttribute("uploaded")).isNotNull();

        KnowledgeDocument indexedDoc = document(2, KnowledgeDocument.Status.INDEXED, null);
        when(documents.ingest(any())).thenReturn(indexedDoc);
        Model ok = new ExtendedModelMap();
        fragments.uploadDocument(file(), ok);
        assertThat(ok.getAttribute("error")).isNull();

        when(documents.ingest(any())).thenThrow(new KnowledgeException("Embedding-Dienst nicht erreichbar"));
        Model knowledge = new ExtendedModelMap();
        fragments.uploadDocument(file(), knowledge);
        assertThat(knowledge.getAttribute("error")).isEqualTo("Embedding-Dienst nicht erreichbar");
        assertThat(knowledge.getAttribute("documents")).isNotNull();

        doThrow(new IllegalStateException("kaputt")).when(documents).ingest(any());
        Model unexpected = new ExtendedModelMap();
        fragments.uploadDocument(file(), unexpected);
        assertThat(unexpected.getAttribute("error")).isEqualTo("Vorgang fehlgeschlagen, Details im Log");
    }

    @Test
    void deleteUnknownDocumentOrFactAndExceptionsBecomeErrorAttribute() {
        KnowledgeDocument existing = document(1, KnowledgeDocument.Status.INDEXED, null);
        when(documents.list()).thenReturn(List.of(existing));
        Model unknownDoc = new ExtendedModelMap();
        assertThat(fragments.deleteDocument(99, unknownDoc)).isEqualTo("fragments/document-list :: list");
        assertThat(unknownDoc.getAttribute("error")).isEqualTo("Dokument nicht gefunden");
        verify(documents, never()).delete(99);

        doThrow(new IllegalStateException("kaputt")).when(documents).delete(1);
        Model brokenDoc = new ExtendedModelMap();
        fragments.deleteDocument(1, brokenDoc);
        assertThat(brokenDoc.getAttribute("error")).isEqualTo("Vorgang fehlgeschlagen, Details im Log");
        assertThat(brokenDoc.getAttribute("documents")).isNotNull();

        Model unknownFact = new ExtendedModelMap();
        assertThat(fragments.deleteFact(99, unknownFact)).isEqualTo("fragments/fact-list :: list");
        assertThat(unknownFact.getAttribute("error")).isEqualTo("Fakt nicht gefunden");
        verify(facts, never()).delete(99);

        MemoryFact fact = mock(MemoryFact.class);
        when(fact.getId()).thenReturn(4L);
        when(facts.list()).thenReturn(List.of(fact));
        doThrow(new IllegalStateException("kaputt")).when(facts).delete(4);
        Model brokenFact = new ExtendedModelMap();
        fragments.deleteFact(4, brokenFact);
        assertThat(brokenFact.getAttribute("error")).isEqualTo("Vorgang fehlgeschlagen, Details im Log");
    }

    private StatusService.StatusReport report() {
        return new StatusService.StatusReport(java.time.Instant.now(), ChatProvider.OLLAMA,
                List.of(new StatusService.Light("ollama", "Ollama", StatusService.State.UP, "erreichbar")),
                List.of("qwen3:8b"), List.of(), null, 0.25, 1, 2,
                new de.grauk.jarvis.status.MetricsService.Latencies(null, 10L, null));
    }

    @Test
    void statusPageAndFragmentExposeStatusReport() {
        when(statusService.report()).thenReturn(report());

        assertThat(dashboard.status(model)).isEqualTo("status");
        assertThat(model.asMap()).containsKeys("status", "settings", "cloudActive");

        Model fragment = new ExtendedModelMap();
        assertThat(fragments.status(fragment)).isEqualTo("fragments/status :: status");
        assertThat(fragment.asMap()).containsOnlyKeys("status");
        Object lights = new SpelExpressionParser().parseExpression("lights[0].state.name()").getValue(fragment.getAttribute("status"));
        assertThat(lights).isEqualTo("UP");
        Object llm = new SpelExpressionParser().parseExpression("latencies.llmMs").getValue(fragment.getAttribute("status"));
        assertThat(llm).isEqualTo(10L);
    }

    @Test
    void apiStatusReturnsReportAsJson() throws Exception {
        when(statusService.report()).thenReturn(report());
        org.springframework.test.web.servlet.MockMvc mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new StatusController(statusService)).build();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/status"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.lights[0].key").value("ollama"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.latencies.llmMs").value(10));
    }
}
