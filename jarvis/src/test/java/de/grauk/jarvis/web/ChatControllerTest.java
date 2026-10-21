package de.grauk.jarvis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import de.grauk.jarvis.assistant.AssistantEvent;
import de.grauk.jarvis.assistant.AssistantService;
import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ToolCallRecord;
import de.grauk.jarvis.conversation.Conversation;
import de.grauk.jarvis.conversation.ConversationNotFoundException;
import de.grauk.jarvis.conversation.ConversationService;
import de.grauk.jarvis.conversation.ConversationService.MessageView;
import de.grauk.jarvis.conversation.Message;
import de.grauk.jarvis.conversation.Role;
import de.grauk.jarvis.conversation.ToolCall;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@WebMvcTest(ChatController.class)
@EnableConfigurationProperties(JarvisWebProperties.class)
class ChatControllerTest {

    private static final String ORIGIN = "http://localhost"; // MockMvc-Port 80; der Filter erlaubt nur den eigenen Port

    @Autowired MockMvc mvc;
    @Autowired ChatController controller;
    @MockitoBean AssistantService assistant;
    @MockitoBean ConversationService conversations;
    @MockitoBean de.grauk.jarvis.status.MetricsService metrics;

    private static Conversation conversation(long id, String title) {
        Conversation c = new Conversation(title);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private static Message message(long id, long conversationId, Role role, String text) {
        Message m = new Message(conversationId, role, text);
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private MvcResult sendMessage(long id, String json) throws Exception {
        return mvc.perform(post("/api/conversations/{id}/messages", id).header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private void awaitBody(MvcResult result, String expected) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(result)).contains(expected));
    }

    // ---- CRUD ----

    @Test
    void listsConversations() throws Exception {
        when(conversations.list()).thenReturn(List.of(conversation(2, "B"), conversation(1, "A")));

        mvc.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].title").value("B"));
    }

    @Test
    void createsConversationWithLocation() throws Exception {
        when(conversations.create(isNull())).thenReturn(conversation(7, "Neue Unterhaltung"));

        mvc.perform(post("/api/conversations").header("Origin", ORIGIN))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/conversations/7"))
                .andExpect(jsonPath("$.title").value("Neue Unterhaltung"));
    }

    @Test
    void renamesAndDeletes() throws Exception {
        when(conversations.rename(3, "Neu")).thenReturn(conversation(3, "Neu"));

        mvc.perform(put("/api/conversations/3").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Neu\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Neu"));
        mvc.perform(delete("/api/conversations/3").header("Origin", ORIGIN)).andExpect(status().isNoContent());
        verify(conversations).delete(3);
    }

    @Test
    void renameWithBlankTitleIs400() throws Exception {
        mvc.perform(put("/api/conversations/3").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void unknownConversationIs404ProblemDetail() throws Exception {
        when(conversations.get(99)).thenThrow(new ConversationNotFoundException(99));

        mvc.perform(get("/api/conversations/99"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Konversation 99 nicht gefunden"));
    }

    @Test
    void messagesIncludeToolCalls() throws Exception {
        Message answer = message(2, 1, Role.ASSISTANT, "Es ist 12 Uhr.");
        ToolCall call = new ToolCall(2, "getCurrentDateTime", "{}", "12:00");
        when(conversations.messages(1)).thenReturn(List.of(
                new MessageView(message(1, 1, Role.USER, "Uhrzeit?"), List.of()),
                new MessageView(answer, List.of(call))));

        mvc.perform(get("/api/conversations/1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[1].toolCalls[0].toolName").value("getCurrentDateTime"));
    }

    // ---- Chat-Stream ----

    @Test
    void blankTextIs400AndNothingIsStored() throws Exception {
        mvc.perform(post("/api/conversations/1/messages").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(conversations, never()).saveUserMessage(anyLong(), any(), any(), any());
        verify(assistant, never()).stream(anyLong(), any());
    }

    @Test
    void unknownConversationOnSendIs404EvenForEventStreamClients() throws Exception {
        when(conversations.saveUserMessage(eq(99L), any(), any(), any())).thenThrow(new ConversationNotFoundException(99));

        mvc.perform(post("/api/conversations/99/messages").header("Origin", ORIGIN)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Hallo\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        verify(assistant, never()).stream(anyLong(), any());
    }

    @Test
    void streamsEventsAndPersistsAnswerOnDone() throws Exception {
        var done = new AssistantEvent.Done("Hallo Welt", ChatProvider.ANTHROPIC, "claude-sonnet-5-5", 321,
                List.of(new ToolCallRecord("getCurrentDateTime", "{}", "12:00")));
        UUID requestId = UUID.randomUUID();
        when(assistant.stream(eq(1L), eq("Hallo"))).thenReturn(Flux.just(
                new AssistantEvent.Warning("RAG nicht verfügbar"),
                new AssistantEvent.Token("Hallo "),
                new AssistantEvent.ToolCalled("getCurrentDateTime", "{}"),
                new AssistantEvent.ConfirmationRequired(requestId, "openApplication", "{\"name\":\"notepad\"}"),
                new AssistantEvent.Token("Welt"),
                done));
        when(conversations.saveAssistantMessage(1, done)).thenReturn(new MessageView(
                message(42, 1, Role.ASSISTANT, "Hallo Welt"),
                List.of(new ToolCall(42, "getCurrentDateTime", "{}", "12:00"))));

        MvcResult result = sendMessage(1, "{\"text\":\" Hallo \",\"language\":\"de\",\"sttMs\":150}");
        awaitBody(result, "event:done");

        assertThat(result.getRequest().isAsyncStarted()).isTrue();
        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        String body = body(result);
        assertThat(body).contains("event:warning", "event:token", "event:tool", "event:confirm");
        assertThat(body).contains("\"text\":\"Hallo \"").contains(requestId.toString());
        assertThat(body.indexOf("event:warning")).isLessThan(body.indexOf("event:token"));
        assertThat(body.indexOf("event:token")).isLessThan(body.indexOf("event:done"));
        assertThat(body).contains("\"messageId\":42", "\"provider\":\"ANTHROPIC\"", "\"model\":\"claude-sonnet-5-5\"",
                "\"toolName\":\"getCurrentDateTime\"");
        verify(conversations).saveUserMessage(1L, "Hallo", "de", 150L);
        verify(conversations).saveAssistantMessage(1, done);
        verify(metrics).recordStt(150L);
        verify(metrics).recordLlm(321L);
    }

    @Test
    void errorEventEndsStreamWithoutPersistingAnswer() throws Exception {
        when(assistant.stream(eq(1L), any())).thenReturn(
                Flux.just(new AssistantEvent.Error("LLM-Server nicht erreichbar")));

        MvcResult result = sendMessage(1, "{\"text\":\"Hallo\"}");
        awaitBody(result, "event:error");

        assertThat(body(result)).contains("LLM-Server nicht erreichbar").doesNotContain("event:done");
        verify(conversations, never()).saveAssistantMessage(anyLong(), any());
    }

    @Test
    void persistenceFailureBecomesErrorEventNotDone() throws Exception {
        var done = new AssistantEvent.Done("Hi", ChatProvider.OLLAMA, "m", 1, List.of());
        when(assistant.stream(eq(1L), any())).thenReturn(Flux.just(done));
        when(conversations.saveAssistantMessage(1, done)).thenThrow(new IllegalStateException("db kaputt"));

        MvcResult result = sendMessage(1, "{\"text\":\"Hallo\"}");
        awaitBody(result, "event:error");

        assertThat(body(result)).doesNotContain("event:done").doesNotContain("db kaputt");
    }

    @Test
    void unexpectedFluxErrorBecomesErrorEvent() throws Exception {
        when(assistant.stream(eq(1L), any())).thenReturn(Flux.error(new RuntimeException("boom")));

        MvcResult result = sendMessage(1, "{\"text\":\"Hallo\"}");
        awaitBody(result, "event:error");

        assertThat(body(result)).doesNotContain("boom");
    }

    @Test
    void clientDisconnectCancelsTheAssistantStream() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Sinks.Many<AssistantEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(assistant.stream(eq(1L), any())).thenReturn(sink.asFlux().doOnCancel(() -> cancelled.set(true)));

        MvcResult result = sendMessage(1, "{\"text\":\"Hallo\"}");
        sink.tryEmitNext(new AssistantEvent.Token("A"));
        awaitBody(result, "event:token");

        result.getRequest().getAsyncContext().complete();

        await().atMost(Duration.ofSeconds(5)).untilTrue(cancelled);
        verify(conversations, never()).saveAssistantMessage(anyLong(), any());
    }

    // ---- Stream-Vertrag: genau ein done/error, Timeout, Heartbeat, Parallelität ----

    private MvcResult openStream(long id, Sinks.Many<AssistantEvent> sink) throws Exception {
        when(assistant.stream(eq(id), any())).thenReturn(sink.asFlux());
        return sendMessage(id, "{\"text\":\"Hallo\"}");
    }

    @Test
    void timeoutSendsExactlyOneErrorEvent() throws Exception {
        Sinks.Many<AssistantEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        var original = (JarvisWebProperties) ReflectionTestUtils.getField(controller, "properties");
        ReflectionTestUtils.setField(controller, "properties",
                new JarvisWebProperties(original.allowedHosts(), Duration.ofMillis(300), original.allowedOrigins()));
        MvcResult result;
        try {
            result = openStream(1, sink);
        } finally {
            ReflectionTestUtils.setField(controller, "properties", original);
        }
        sink.tryEmitNext(new AssistantEvent.Token("A"));
        awaitBody(result, "event:token");

        awaitBody(result, "event:error");
        String body = body(result);
        assertThat(body).contains("Zeitüberschreitung").doesNotContain("event:done");
        assertThat(body.split("event:error", -1)).hasSize(2);
        verify(conversations, never()).saveAssistantMessage(anyLong(), any());
    }

    @Test
    void completionWithoutDoneBecomesErrorEvent() throws Exception {
        when(assistant.stream(eq(1L), any())).thenReturn(Flux.just(new AssistantEvent.Token("A")));

        MvcResult result = sendMessage(1, "{\"text\":\"Hallo\"}");
        awaitBody(result, "event:error");

        assertThat(body(result)).contains("Antwort unvollständig beendet").doesNotContain("event:done");
        verify(conversations, never()).saveAssistantMessage(anyLong(), any());
    }

    @Test
    void metricsFailureDoesNotPreventDone() throws Exception {
        var done = new AssistantEvent.Done("Hi", ChatProvider.OLLAMA, "m", 5, List.of());
        when(assistant.stream(eq(1L), any())).thenReturn(Flux.just(done));
        when(conversations.saveAssistantMessage(1, done)).thenReturn(
                new MessageView(message(9, 1, Role.ASSISTANT, "Hi"), List.of()));
        doThrow(new IllegalStateException("metrics kaputt")).when(metrics).recordLlm(5L);

        MvcResult result = sendMessage(1, "{\"text\":\"Hallo\"}");
        awaitBody(result, "event:done");

        assertThat(body(result)).doesNotContain("event:error");
    }

    @Test
    void sendsHeartbeatCommentsWhileStreaming() throws Exception {
        Sinks.Many<AssistantEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        ReflectionTestUtils.setField(controller, "heartbeatInterval", Duration.ofMillis(30));
        try {
            MvcResult result = openStream(1, sink);
            awaitBody(result, ":keepalive");
        } finally {
            ReflectionTestUtils.setField(controller, "heartbeatInterval", ChatController.DEFAULT_HEARTBEAT);
            sink.tryEmitNext(new AssistantEvent.Error("Ende"));
        }
    }

    @Test
    void secondStreamForSameConversationIs409() throws Exception {
        Sinks.Many<AssistantEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        MvcResult first = openStream(5, sink);

        mvc.perform(post("/api/conversations/5/messages").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Nochmal\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Antwort läuft noch"));
        verify(conversations, times(1)).saveUserMessage(eq(5L), any(), any(), any());

        // nach dem Ende ist die Konversation wieder frei
        sink.tryEmitNext(new AssistantEvent.Error("Ende"));
        awaitBody(first, "event:error");
        when(assistant.stream(eq(5L), any())).thenReturn(Flux.just(new AssistantEvent.Error("noch einer")));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(post("/api/conversations/5/messages").header("Origin", ORIGIN)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Nochmal\"}"))
                        .andExpect(request().asyncStarted()));
    }

    @Test
    void deleteDuringActiveStreamIs409() throws Exception {
        Sinks.Many<AssistantEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        MvcResult stream = openStream(6, sink);

        mvc.perform(delete("/api/conversations/6").header("Origin", ORIGIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Antwort läuft noch"));
        verify(conversations, never()).delete(6);

        sink.tryEmitNext(new AssistantEvent.Error("Ende"));
        awaitBody(stream, "event:error");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(delete("/api/conversations/6").header("Origin", ORIGIN)).andExpect(status().isNoContent()));
    }

    @Test
    void failedSaveOfUserMessageReleasesTheConversation() throws Exception {
        when(conversations.saveUserMessage(eq(8L), any(), any(), any())).thenThrow(new ConversationNotFoundException(8));

        mvc.perform(post("/api/conversations/8/messages").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Hallo\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/conversations/8/messages").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Hallo\"}")).andExpect(status().isNotFound());
    }

    // ---- Schutz ----

    @Test
    void stateChangingRequestWithoutOriginIs403() throws Exception {
        mvc.perform(post("/api/conversations")).andExpect(status().isForbidden());
        mvc.perform(post("/api/conversations/1/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Hallo\"}")).andExpect(status().isForbidden());

        verify(conversations, never()).create(any());
        verify(conversations, never()).saveUserMessage(anyLong(), any(), any(), any());
    }
}
