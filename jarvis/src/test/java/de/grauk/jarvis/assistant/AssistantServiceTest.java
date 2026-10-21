package de.grauk.jarvis.assistant;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import de.grauk.jarvis.settings.SettingsService;
import de.grauk.jarvis.settings.SettingsService.SettingsSnapshot;
import de.grauk.jarvis.tools.ToolInvocationListener;
import de.grauk.jarvis.tools.ToolRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ToolContext;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantServiceTest {

    /** Fake-Modell: streamt die vorgegebenen Teile und merkt sich alle Prompts. */
    static class FakeChatModel implements ChatModel {
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        final List<String> chunks;

        FakeChatModel(String... chunks) {
            this.chunks = List.of(chunks);
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(String.join("", chunks)))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return Flux.fromIterable(chunks)
                    .map(c -> new ChatResponse(List.of(new Generation(new AssistantMessage(c)))));
        }
    }

    private final SettingsService settings = mock(SettingsService.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
            MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository())
                    .maxMessages(20).build()).build();

    @BeforeEach
    void setUp() {
        when(settings.current()).thenReturn(new SettingsSnapshot(ChatProvider.OLLAMA, "qwen3:8b", "System", 0.7, false));
        when(toolRegistry.enabledCallbacks()).thenReturn(List.of());
    }

    private AssistantService service(ChatModel ollama, ChatModel anthropic, String anthropicKey) {
        GuardedRagAdvisor rag = new GuardedRagAdvisor(QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().similarityThreshold(0.6).topK(4).build())
                .promptTemplate(PromptTemplates.ragTemplate()).build());
        Map<ChatProvider, ChatClient> clients = new java.util.EnumMap<>(ChatProvider.class);
        clients.put(ChatProvider.OLLAMA, ChatClient.builder(ollama).defaultAdvisors(memoryAdvisor, rag).build());
        if (anthropic != null) {
            clients.put(ChatProvider.ANTHROPIC, ChatClient.builder(anthropic).defaultAdvisors(memoryAdvisor, rag).build());
        }
        @SuppressWarnings("unchecked")
        ObjectProvider<OllamaApi> api = mock(ObjectProvider.class);
        ChatProviderRegistry registry = new ChatProviderRegistry(new ChatClients(clients), settings,
                new AssistantProperties(null, null, null), api, anthropicKey);
        return new AssistantService(registry, settings, toolRegistry);
    }

    private static List<AssistantEvent> collect(Flux<AssistantEvent> flux) {
        return flux.collectList().block(Duration.ofSeconds(10));
    }

    @Test
    void streamsTokensAndEndsWithDone() {
        FakeChatModel model = new FakeChatModel("Hal", "lo ", "Welt");
        List<AssistantEvent> events = collect(service(model, null, "").stream(1, "Hi"));

        assertThat(events.subList(0, 3)).containsExactly(
                new AssistantEvent.Token("Hal"), new AssistantEvent.Token("lo "), new AssistantEvent.Token("Welt"));
        assertThat(events.getLast()).isInstanceOfSatisfying(AssistantEvent.Done.class, done -> {
            assertThat(done.fullText()).isEqualTo("Hallo Welt");
            assertThat(done.provider()).isEqualTo(ChatProvider.OLLAMA);
            assertThat(done.model()).isEqualTo("qwen3:8b");
            assertThat(done.toolCalls()).isEmpty();
        });
        assertThat(events).hasSize(4);
    }

    @Test
    void followUpContainsPreviousExchange() {
        FakeChatModel model = new FakeChatModel("Antwort Eins");
        AssistantService service = service(model, null, "");
        collect(service.stream(7, "Erste Frage"));
        collect(service.stream(7, "Zweite Frage"));
        collect(service.stream(8, "Andere Konversation"));

        List<String> second = model.prompts.get(1).getInstructions().stream().map(Message::getText).toList();
        assertThat(second).contains("Erste Frage", "Antwort Eins");
        List<String> other = model.prompts.get(2).getInstructions().stream().map(Message::getText).toList();
        assertThat(other).doesNotContain("Erste Frage");
    }

    @Test
    void unavailableProviderYieldsErrorAndDoesNotCallOtherProvider() {
        when(settings.current()).thenReturn(new SettingsSnapshot(ChatProvider.ANTHROPIC, "claude-sonnet-5-5", "S", 0.7, false));
        FakeChatModel ollama = new FakeChatModel("nie");
        FakeChatModel anthropic = new FakeChatModel("nie");

        List<AssistantEvent> events = collect(service(ollama, anthropic, "").stream(1, "Hi"));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(AssistantEvent.Error.class);
        assertThat(ollama.prompts).isEmpty();
        assertThat(anthropic.prompts).isEmpty();
    }

    @Test
    void ragFailureWarnsButStillAnswers() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("Embedding-Server weg"));
        FakeChatModel model = new FakeChatModel("Trotzdem", " da");

        List<AssistantEvent> events = collect(service(model, null, "").stream(1, "Hi"));

        assertThat(events).anyMatch(e -> e instanceof AssistantEvent.Warning);
        assertThat(events.getLast()).isInstanceOfSatisfying(AssistantEvent.Done.class,
                done -> assertThat(done.fullText()).isEqualTo("Trotzdem da"));
    }

    @Test
    void modelFailureBecomesGermanErrorEventAndFluxCompletes() {
        ChatModel broken = new FakeChatModel() {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new org.springframework.web.client.ResourceAccessException("I/O error",
                        new java.net.ConnectException("Connection refused")));
            }
        };
        List<AssistantEvent> events = collect(service(broken, null, "").stream(1, "Hi"));

        assertThat(events).containsExactly(
                new AssistantEvent.Error("LLM-Server nicht erreichbar – in den Einstellungen kann Claude gewählt werden"));
    }

    @Test
    void toolListenerEventsAppearInStream() {
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("timer").description("Timer").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                ToolInvocationListener l = (ToolInvocationListener) toolContext.getContext()
                        .get(ToolInvocationListener.CONTEXT_KEY);
                l.onToolCalled("timer", toolInput);
                l.onConfirmationRequired(UUID.fromString("00000000-0000-0000-0000-000000000001"), "timer", toolInput);
                l.onToolCompleted("timer", toolInput, "ok");
                return "ok";
            }
        };
        when(toolRegistry.enabledCallbacks()).thenReturn(List.of(tool));

        FakeChatModel model = new FakeChatModel("Timer läuft") {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                prompts.add(prompt);
                Message last = prompt.getInstructions().getLast();
                if (last instanceof ToolResponseMessage) {
                    return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Timer läuft")))));
                }
                AssistantMessage call = AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "timer", "{\"minutes\":5}")))
                        .build();
                return Flux.just(new ChatResponse(List.of(new Generation(call))));
            }
        };

        List<AssistantEvent> events = collect(service(model, null, "").stream(1, "Timer 5 Minuten"));

        assertThat(events).contains(new AssistantEvent.ToolCalled("timer", "{\"minutes\":5}"));
        assertThat(events).anyMatch(e -> e instanceof AssistantEvent.ConfirmationRequired);
        assertThat(events.getLast()).isInstanceOfSatisfying(AssistantEvent.Done.class, done -> {
            assertThat(done.fullText()).isEqualTo("Timer läuft");
            assertThat(done.toolCalls()).containsExactly(new ToolCallRecord("timer", "{\"minutes\":5}", "ok"));
        });
    }

    @Test
    void blankAnswerYieldsError() {
        List<AssistantEvent> events = collect(service(new FakeChatModel(""), null, "").stream(1, "Hi"));

        assertThat(events).containsExactly(new AssistantEvent.Error("Das Modell hat keine Antwort geliefert."));
    }

    @Test
    void userMessageDistinguishesUnreachableTimeoutAndHttpStatus() {
        var wrapped = new org.springframework.web.client.ResourceAccessException("io",
                new java.net.ConnectException("refused"));
        assertThat(AssistantService.userMessage(ChatProvider.OLLAMA, wrapped)).startsWith("LLM-Server nicht erreichbar");
        assertThat(AssistantService.userMessage(ChatProvider.ANTHROPIC, new RuntimeException(
                new java.net.UnknownHostException("x")))).startsWith("Claude ist nicht erreichbar");

        var timeout = new org.springframework.web.client.ResourceAccessException("io",
                new java.net.SocketTimeoutException("Read timed out"));
        assertThat(AssistantService.userMessage(ChatProvider.OLLAMA, timeout))
                .isEqualTo("Zeitüberschreitung – der LLM-Server antwortet nicht.");
        assertThat(AssistantService.userMessage(ChatProvider.ANTHROPIC, new java.util.concurrent.TimeoutException()))
                .isEqualTo("Zeitüberschreitung – Claude antwortet nicht.");

        assertThat(AssistantService.userMessage(ChatProvider.ANTHROPIC, status(401))).isEqualTo("Claude: API-Key ungültig");
        assertThat(AssistantService.userMessage(ChatProvider.OLLAMA, status(404))).startsWith("Modell nicht gefunden");
        assertThat(AssistantService.userMessage(ChatProvider.OLLAMA, status(429))).startsWith("Rate-Limit erreicht");
        assertThat(AssistantService.userMessage(ChatProvider.OLLAMA, status(500)))
                .startsWith("Die Anfrage ist fehlgeschlagen");
    }

    @Test
    void userMessageNeverContainsNull() {
        assertThat(AssistantService.userMessage(ChatProvider.OLLAMA, new IllegalStateException()))
                .isEqualTo("Die Anfrage ist fehlgeschlagen: IllegalStateException");
        assertThat(AssistantService.userMessage(null, new IllegalStateException(" ")))
                .isEqualTo("Die Anfrage ist fehlgeschlagen: IllegalStateException");
    }

    private static Throwable status(int code) {
        return org.springframework.web.client.HttpServerErrorException.create(
                org.springframework.http.HttpStatusCode.valueOf(code), "s", org.springframework.http.HttpHeaders.EMPTY,
                new byte[0], null);
    }
}
