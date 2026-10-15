package de.grauk.jarvis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ChatProviderRegistry;
import de.grauk.jarvis.knowledge.DocumentIngestionService;
import de.grauk.jarvis.knowledge.FactMemory;
import de.grauk.jarvis.knowledge.KnowledgeDocument;
import de.grauk.jarvis.knowledge.MemoryFact;
import de.grauk.jarvis.settings.AssistantSettings;
import de.grauk.jarvis.settings.SettingsService;
import de.grauk.jarvis.tools.ConfirmationGate;
import de.grauk.jarvis.tools.ConfirmationNotFoundException;
import de.grauk.jarvis.tools.TimerExpiredEvent;
import de.grauk.jarvis.tools.ToolRegistry;
import de.grauk.jarvis.tools.ToolRegistry.ToolInfo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest({ToolController.class, KnowledgeController.class, SettingsController.class, EventsController.class})
@EnableConfigurationProperties(JarvisWebProperties.class)
@Import({SettingsWebService.class, DashboardEvents.class})
class ApiControllersTest {

    private static final String ORIGIN = "http://127.0.0.1"; // MockMvc-Port 80; der Filter erlaubt nur den eigenen Port

    @Autowired MockMvc mvc;
    @Autowired DashboardEvents events;
    @MockitoBean ToolRegistry toolRegistry;
    @MockitoBean ConfirmationGate gate;
    @MockitoBean DocumentIngestionService documents;
    @MockitoBean FactMemory facts;
    @MockitoBean SettingsService settings;
    @MockitoBean ChatProviderRegistry registry;

    // ---- Bestätigung ----

    @Test
    void confirmationResolvesGate() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/api/tools/confirmations/{id}", id).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(status().isNoContent());

        verify(gate).resolve(id, true);
    }

    @Test
    void unknownConfirmationIs404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new ConfirmationNotFoundException(id)).when(gate).resolve(id, false);

        mvc.perform(post("/api/tools/confirmations/{id}", id).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidConfirmationRequestsAre400() throws Exception {
        mvc.perform(post("/api/tools/confirmations/not-a-uuid").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tools/confirmations/{id}", UUID.randomUUID()).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tools/confirmations/{id}", UUID.randomUUID()).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("kaputt"))
                .andExpect(status().isBadRequest());

        verify(gate, never()).resolve(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void confirmationFromForeignOriginIs403() throws Exception {
        mvc.perform(post("/api/tools/confirmations/{id}", UUID.randomUUID()).header("Origin", "http://evil.example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(status().isForbidden());

        verify(gate, never()).resolve(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void foreignHostHeaderIs403EvenForGet() throws Exception {
        mvc.perform(get("/api/tools").header("Host", "evil.example.com:8080")).andExpect(status().isForbidden());
    }

    // ---- Tools ----

    @Test
    void listsAndUpdatesTools() throws Exception {
        ToolInfo before = new ToolInfo("openApplication", "Öffnet", true, true);
        when(toolRegistry.all()).thenReturn(List.of(before));

        mvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("openApplication"))
                .andExpect(jsonPath("$[0].requiresConfirmation").value(true));

        mvc.perform(put("/api/tools/openApplication").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"requiresConfirmation\":true}"))
                .andExpect(status().isOk());
        verify(toolRegistry).update("openApplication", false, true);
    }

    @Test
    void invalidToolUpdateIs400WithMessage() throws Exception {
        doThrow(new IllegalArgumentException("Das Tool 'openApplication' erfordert immer eine Bestätigung."))
                .when(toolRegistry).update("openApplication", true, false);

        mvc.perform(put("/api/tools/openApplication").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"requiresConfirmation\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Das Tool 'openApplication' erfordert immer eine Bestätigung."));
    }

    // ---- Wissen ----

    @Test
    void uploadReturnsIndexedDocument() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument("notizen.md");
        ReflectionTestUtils.setField(doc, "id", 5L);
        MockMultipartFile file = new MockMultipartFile("file", "notizen.md", "text/markdown", "# Hallo".getBytes());
        when(documents.ingest(any())).thenReturn(doc);

        mvc.perform(multipart("/api/knowledge/documents").file(file).header("Origin", ORIGIN))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/knowledge/documents/5"))
                .andExpect(jsonPath("$.filename").value("notizen.md"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void uploadWithoutFileOrEmptyFileIs400() throws Exception {
        mvc.perform(multipart("/api/knowledge/documents").header("Origin", ORIGIN)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/knowledge/documents")
                        .file(new MockMultipartFile("file", "leer.txt", "text/plain", new byte[0]))
                        .header("Origin", ORIGIN))
                .andExpect(status().isBadRequest());

        verify(documents, never()).ingest(any());
    }

    @Test
    void deleteDocumentAndFactReturn404WhenUnknown() throws Exception {
        when(documents.list()).thenReturn(List.of());
        when(facts.list()).thenReturn(List.of());

        mvc.perform(delete("/api/knowledge/documents/1").header("Origin", ORIGIN)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/knowledge/facts/1").header("Origin", ORIGIN)).andExpect(status().isNotFound());
        verify(documents, never()).delete(1);
        verify(facts, never()).delete(1);
    }

    @Test
    void deletesExistingDocumentAndFact() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument("a.txt");
        ReflectionTestUtils.setField(doc, "id", 1L);
        MemoryFact fact = new MemoryFact("Ich mag Tee", null);
        ReflectionTestUtils.setField(fact, "id", 2L);
        when(documents.list()).thenReturn(List.of(doc));
        when(facts.list()).thenReturn(List.of(fact));

        mvc.perform(delete("/api/knowledge/documents/1").header("Origin", ORIGIN)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/knowledge/facts/2").header("Origin", ORIGIN)).andExpect(status().isNoContent());
        mvc.perform(get("/api/knowledge/facts")).andExpect(jsonPath("$[0].content").value("Ich mag Tee"));

        verify(documents).delete(1);
        verify(facts).delete(2);
    }

    // ---- Einstellungen ----

    private AssistantSettings stored() {
        return new AssistantSettings(ChatProvider.OLLAMA, "qwen3:8b", "Prompt", 0.7, false, null, "en-voice", true);
    }

    private void stubRegistry() {
        when(registry.available()).thenReturn(Map.of(ChatProvider.OLLAMA, true, ChatProvider.ANTHROPIC, false));
        when(registry.models(ChatProvider.OLLAMA)).thenReturn(List.of("qwen3:8b", "llama3.2"));
        when(registry.models(ChatProvider.ANTHROPIC)).thenReturn(List.of("claude-sonnet-5-5"));
    }

    @Test
    void settingsIncludeAvailabilityAndModelsPerProvider() throws Exception {
        stubRegistry();
        when(settings.getSettings()).thenReturn(stored());

        mvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.provider").value("OLLAMA"))
                .andExpect(jsonPath("$.available.OLLAMA").value(true))
                .andExpect(jsonPath("$.available.ANTHROPIC").value(false))
                .andExpect(jsonPath("$.models.OLLAMA.length()").value(2))
                .andExpect(jsonPath("$.models.ANTHROPIC[0]").value("claude-sonnet-5-5"));
    }

    @Test
    void settingsUpdateIsPartialAndKeepsOtherValues() throws Exception {
        stubRegistry();
        AssistantSettings existing = stored();
        when(settings.getSettings()).thenReturn(existing);
        when(settings.update(any(AssistantSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/settings").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"temperature\":0.2,\"voiceEn\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.temperature").value(0.2))
                .andExpect(jsonPath("$.settings.model").value("qwen3:8b"));

        var captor = org.mockito.ArgumentCaptor.forClass(AssistantSettings.class);
        verify(settings).update(captor.capture());
        assertThat(captor.getValue().getVoiceEn()).isNull();
        assertThat(captor.getValue().getSystemPrompt()).isEqualTo("Prompt");
        assertThat(captor.getValue().isTtsEnabled()).isTrue();
    }

    @Test
    void switchingProviderWithoutModelPicksFirstModelOfNewProvider() throws Exception {
        stubRegistry();
        when(settings.getSettings()).thenReturn(stored());
        when(settings.update(any(AssistantSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/settings").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"ANTHROPIC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.provider").value("ANTHROPIC"))
                .andExpect(jsonPath("$.settings.model").value("claude-sonnet-5-5"));
    }

    @Test
    void invalidSettingsAre400WithMessage() throws Exception {
        stubRegistry();
        when(settings.getSettings()).thenReturn(stored());
        when(settings.update(any(AssistantSettings.class)))
                .thenThrow(new IllegalArgumentException("Temperatur muss zwischen 0.0 und 2.0 liegen"));

        mvc.perform(put("/api/settings").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"temperature\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Temperatur muss zwischen 0.0 und 2.0 liegen"));
        mvc.perform(put("/api/settings").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"GPT\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- Dashboard-Events ----

    @Test
    void timerEventReachesConnectedClientsAndDeadOnesAreDropped() throws Exception {
        MvcResult live = mvc.perform(get("/api/events").accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        var dead = events.subscribe();
        dead.complete();
        // einen kaputten Emitter simulieren: send() auf einem fertigen Emitter wirft IllegalStateException
        assertThat(events.subscriberCount()).isGreaterThanOrEqualTo(1);

        events.onTimerExpired(new TimerExpiredEvent(UUID.randomUUID(), "Nudeln", 10, Instant.parse("2026-01-01T12:00:00Z")));

        String body = live.getResponse().getContentAsString();
        assertThat(body).contains("event:ready").contains("event:timer").contains("\"label\":\"Nudeln\"")
                .contains("\"minutes\":10").contains("2026-01-01T12:00:00Z");
        assertThat(events.subscriberCount()).isEqualTo(1);
    }
}
