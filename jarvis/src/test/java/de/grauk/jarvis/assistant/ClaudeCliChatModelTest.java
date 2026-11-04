package de.grauk.jarvis.assistant;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCliChatModelTest {

    private static final String DELTA = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"%s\"}}}";
    private static final String RESULT_OK = "{\"type\":\"result\",\"is_error\":false,\"result\":\"x\"}";

    private final ClaudeToolBridge bridge = new ClaudeToolBridge();

    private ClaudeCliChatModel model(FakeClaudeCli cli, Duration idleTimeout) {
        return new ClaudeCliChatModel(cli, bridge, new ClaudeCliProperties("claude", idleTimeout), () -> 9090);
    }

    private ClaudeCliChatModel model(FakeClaudeCli cli) {
        return model(cli, Duration.ofSeconds(10));
    }

    private static Prompt prompt(String text) {
        return new Prompt(List.of(new SystemMessage("Du bist Jarvis."), new UserMessage(text)),
                ClaudeCliChatOptions.builder().model("claude-test").build());
    }

    private static ToolCallback tool(String name) {
        ToolDefinition definition = DefaultToolDefinition.builder().name(name).description("d")
                .inputSchema("{\"type\":\"object\"}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "Ergebnis";
            }
        };
    }

    @Test
    void streamsTextAndPassesPromptOverStdinAndFiles() {
        FakeClaudeCli cli = FakeClaudeCli.printing(0, DELTA.formatted("Hal"), DELTA.formatted("lo äöü"), RESULT_OK);

        List<ChatResponse> responses = model(cli).stream(prompt("Grüß dich")).collectList().block();

        assertThat(responses).extracting(r -> r.getResult().getOutput().getText()).containsExactly("Hal", "lo äöü");
        assertThat(cli.process().stdin()).isEqualTo("Grüß dich");
        assertThat(cli.systemPrompt).isEqualTo("Du bist Jarvis.");
        assertThat(cli.mcpToolTimeout).isEqualTo("10000");
    }

    @Test
    void switchesOffEverythingTheCliBringsAlong() {
        FakeClaudeCli cli = FakeClaudeCli.printing(0, RESULT_OK);

        model(cli).stream(prompt("Hi")).blockLast();

        assertThat(cli.command).contains("-p", "--tools=", "--setting-sources=", "--strict-mcp-config",
                "--disable-slash-commands", "--no-session-persistence");
        assertThat(cli.command).containsSequence("--model", "claude-test");
        assertThat(cli.command).as("leere Argumente gehen unter Windows verloren").doesNotContain("");
    }

    @Test
    void withoutToolsThereIsNoMcpServer() {
        FakeClaudeCli cli = FakeClaudeCli.printing(0, RESULT_OK);

        model(cli).stream(prompt("Hi")).blockLast();

        assertThat(cli.command).doesNotContain("--mcp-config");
        assertThat(cli.mcpConfig).isNull();
    }

    /** Der brüchige Teil: Modell, Denkaufwand, Tools und Kontext müssen die Options-Mischung des ChatClient überleben. */
    @Test
    void chatClientDeliversModelThinkingToolsAndContextToTheRun() {
        AtomicReference<ClaudeToolBridge.ToolSession> session = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        FakeClaudeCli cli = FakeClaudeCli.printing(0, DELTA.formatted("ok"), RESULT_OK).onStart(c -> {
            token.set(c.mcpConfig.replaceAll("(?s).*Bearer ([0-9a-f-]+).*", "$1"));
            session.set(bridge.find(token.get()).orElseThrow());
        });

        String text = ChatClient.builder(model(cli)).build().prompt()
                .system("S").user("Hi")
                .options(ClaudeCliChatOptions.builder().model("claude-test").thinking(true))
                .tools((Object[]) new ToolCallback[] {tool("getTime")})
                .toolContext(Map.of("key", "value"))
                .stream().content().collectList().map(parts -> String.join("", parts)).block();

        assertThat(text).isEqualTo("ok");
        assertThat(cli.command).containsSequence("--model", "claude-test");
        assertThat(cli.command).containsSequence("--effort", "high");
        assertThat(cli.command).containsSequence("--mcp-config", "mcp.json");
        assertThat(cli.command).containsSequence("--allowedTools", "mcp__jarvis");
        assertThat(cli.mcpConfig).contains("http://127.0.0.1:9090/internal/claude-mcp");
        assertThat(session.get().tools()).containsOnlyKeys("getTime");
        assertThat(session.get().context().getContext()).containsEntry("key", "value");
        assertThat(bridge.find(token.get())).as("Token gilt nur während des Laufs").isEmpty();
    }

    @Test
    void thinkingOffUsesLowEffort() {
        FakeClaudeCli cli = FakeClaudeCli.printing(0, RESULT_OK);

        model(cli).stream(new Prompt(List.of(new UserMessage("Hi")),
                ClaudeCliChatOptions.builder().thinking(false).build())).blockLast();

        assertThat(cli.command).containsSequence("--effort", "low");
    }

    @Test
    void removesItsWorkingDirectoryAfterTheRun() {
        FakeClaudeCli cli = FakeClaudeCli.printing(0, RESULT_OK);

        model(cli).stream(prompt("Hi")).blockLast();

        assertThat(cli.workDir).doesNotExist();
    }

    @Test
    void cliFailureBecomesProviderUnavailable() {
        FakeClaudeCli cli = FakeClaudeCli.printing(1,
                "{\"type\":\"assistant\",\"error\":\"authentication_failed\",\"message\":{\"content\":[]}}",
                "{\"type\":\"result\",\"is_error\":true,\"api_error_status\":null,\"result\":\"Not logged in\"}");

        assertThatThrownBy(() -> model(cli).stream(prompt("Hi")).blockLast())
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("claude auth login");
    }

    @Test
    void crashWithoutResultEventIsAnError() {
        FakeClaudeCli cli = FakeClaudeCli.printing(3, DELTA.formatted("Hal"));

        assertThatThrownBy(() -> model(cli).stream(prompt("Hi")).blockLast())
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("Exit 3");
    }

    @Test
    void missingCliGivesInstallHintAndRemovesItsFiles() {
        FakeClaudeCli cli = FakeClaudeCli.failingToStart();

        assertThatThrownBy(() -> model(cli).stream(prompt("Hi")).blockLast())
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("Claude Code installieren");
        assertThat(cli.workDir).doesNotExist();
    }

    @Test
    void silentCliRunsIntoTheIdleTimeoutAndIsKilled() throws IOException {
        FakeClaudeCli cli = FakeClaudeCli.hanging();

        assertThatThrownBy(() -> model(cli, Duration.ofMillis(100)).stream(prompt("Hi")).blockLast())
                .hasRootCauseInstanceOf(TimeoutException.class);

        assertThat(cli.process().destroyed()).isTrue();
    }

    /** Abbruch unterbricht den Worker-Thread; das Aufräumen darf daran nicht scheitern (das Verzeichnis enthält das Token). */
    @Test
    void cancellingKillsTheProcessClosesTheBridgeAndRemovesTheFiles() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> token = new AtomicReference<>();
        FakeClaudeCli cli = FakeClaudeCli.hanging().onStart(c -> {
            token.set(c.mcpConfig.replaceAll("(?s).*Bearer ([0-9a-f-]+).*", "$1"));
            started.countDown();
        });
        Prompt prompt = new Prompt(List.of(new UserMessage("Hi")),
                ClaudeCliChatOptions.builder().toolCallbacks(tool("t")).toolContext(new HashMap<>()).build());

        Disposable running = model(cli).stream(prompt).subscribe();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        running.dispose();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Files.exists(cli.workDir) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(cli.process().destroyed()).isTrue();
        assertThat(cli.workDir).doesNotExist();
        assertThat(bridge.find(token.get())).isEmpty();
    }

    @Test
    void callCollectsTheStream() {
        FakeClaudeCli cli = FakeClaudeCli.printing(0, DELTA.formatted("Hal"), DELTA.formatted("lo"), RESULT_OK);

        assertThat(model(cli).call(prompt("Hi")).getResult().getOutput().getText()).isEqualTo("Hallo");
    }

    @Test
    void singleMessageIsSentAsIs() {
        assertThat(ClaudeCliChatModel.userPrompt(List.of(new SystemMessage("S"), new UserMessage("Frage"))))
                .isEqualTo("Frage");
    }

    @Test
    void earlierTurnsBecomeHistoryAndTheLastMessageStaysTheQuestion() {
        List<Message> messages = new ArrayList<>(List.of(new SystemMessage("S"), new UserMessage("Erste"),
                new AssistantMessage("Antwort"), new AssistantMessage(" "), new UserMessage("Zweite")));

        assertThat(ClaudeCliChatModel.userPrompt(messages))
                .contains("<user>\nErste\n</user>\n<assistant>\nAntwort\n</assistant>")
                .endsWith("Aktuelle Nachricht, auf die du antwortest:\nZweite");
    }

    @Test
    void systemMessagesAreJoined() {
        assertThat(ClaudeCliChatModel.systemPrompt(List.of(new SystemMessage("A"), new UserMessage("x"),
                new SystemMessage("B")))).isEqualTo("A\n\nB");
    }
}
