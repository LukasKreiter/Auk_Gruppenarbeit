package de.grauk.jarvis.assistant;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.FileSystemUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude über die Claude-Code-CLI: pro Anfrage ein {@code claude -p}-Prozess, angemeldet über das Abo statt über
 * einen API-Key (ADR 0006). Die CLI ist zustandslos im Sinne von Jarvis: Verlauf und RAG kommen aus den Advisors und
 * gehen als Text an stdin, die Tool-Schleife läuft in der CLI und ruft Jarvis über die {@link ClaudeToolBridge}.
 * Alles Eigene der CLI (Datei-/Shell-Tools, Einstellungen, Skills, Sitzungen) ist abgeschaltet.
 */
public class ClaudeCliChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliChatModel.class);
    private static final JsonMapper JSON = JsonMapper.shared();

    private static final String SYSTEM_PROMPT_FILE = "system-prompt.txt";
    private static final String MCP_CONFIG_FILE = "mcp.json";
    private static final String STDERR_FILE = "stderr.log";

    private final ClaudeCliLauncher launcher;
    private final ClaudeToolBridge bridge;
    private final ClaudeCliProperties properties;
    private final IntSupplier serverPort;

    ClaudeCliChatModel(ClaudeCliLauncher launcher, ClaudeToolBridge bridge, ClaudeCliProperties properties,
                       IntSupplier serverPort) {
        this.launcher = launcher;
        this.bridge = bridge;
        this.properties = properties;
        this.serverPort = serverPort;
    }

    /** Vorlage, in die der ChatClient Modell und Tools mischt; so kommen immer {@link ClaudeCliChatOptions} an. */
    @Override
    public ChatOptions getOptions() {
        return ClaudeCliChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String text = stream(prompt)
                .map(response -> response.getResult().getOutput().getText())
                .collect(Collectors.joining())
                .block();
        return response(text);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.<ChatResponse>create(sink -> run(prompt, sink))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.idleTimeout());
    }

    private void run(Prompt prompt, FluxSink<ChatResponse> sink) {
        ClaudeCliChatOptions options = prompt.getOptions() instanceof ClaudeCliChatOptions o ? o
                : ClaudeCliChatOptions.builder().build();
        List<ToolCallback> tools = options.getToolCallbacks();
        String token = tools.isEmpty() ? null : bridge.open(tools, options.getToolContext());
        Path workDir = null;
        Process process = null;
        Throwable failure = null;
        try {
            // Leeres Arbeitsverzeichnis: keine CLAUDE.md aus dem Projekt, und alle Dateien des Laufs an einem Ort
            workDir = Files.createTempDirectory("jarvis-claude-");
            Files.writeString(workDir.resolve(SYSTEM_PROMPT_FILE), systemPrompt(prompt.getInstructions()));
            if (token != null) {
                Files.writeString(workDir.resolve(MCP_CONFIG_FILE), mcpConfig(token));
            }
            ProcessBuilder builder = launcher.command(arguments(options, token != null))
                    .directory(workDir.toFile())
                    .redirectError(workDir.resolve(STDERR_FILE).toFile());
            // Standard der CLI ist kürzer als eine ausstehende Bestätigung im Chat
            builder.environment().put("MCP_TOOL_TIMEOUT", String.valueOf(properties.idleTimeout().toMillis()));
            process = launcher.start(builder);
            Process running = process;
            sink.onDispose(() -> kill(running));
            sendPrompt(process, userPrompt(prompt.getInstructions()));
            ClaudeStreamParser parser = readAnswer(process, sink);
            failure = failureOf(parser, process.waitFor(), workDir);
        } catch (IOException e) {
            failure = process == null && workDir != null
                    ? new ProviderUnavailableException(ChatProvider.ANTHROPIC,
                            "Claude-CLI nicht startbar – Claude Code installieren oder "
                                    + "jarvis.assistant.claude-cli.executable setzen", e)
                    : e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = e;
        } finally {
            if (token != null) {
                bridge.close(token);
            }
            cleanUp(process, workDir);
        }
        // erst nach dem Aufräumen melden: wer das Ende sieht, kann sich darauf verlassen, dass das Token ungültig ist
        if (sink.isCancelled()) {
            return;
        }
        if (failure != null) {
            sink.error(failure);
        } else {
            sink.complete();
        }
    }

    private static void sendPrompt(Process process, String text) {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Prozess schon beendet: der Grund steht im Exit-Code bzw. im result-Ereignis
            log.debug("Prompt nicht vollständig an die Claude-CLI übergeben: {}", e.toString());
        }
    }

    private static ClaudeStreamParser readAnswer(Process process, FluxSink<ChatResponse> sink) throws IOException {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        try (BufferedReader stdout = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = stdout.readLine()) != null) {
                String text = parser.accept(line);
                if (!text.isEmpty()) {
                    sink.next(response(text));
                }
            }
        }
        return parser;
    }

    /** @return der Fehler des Laufs, {@code null} bei Erfolg. */
    private static ProviderUnavailableException failureOf(ClaudeStreamParser parser, int exitCode, Path workDir) {
        if (parser.failure().isPresent()) {
            return new ProviderUnavailableException(ChatProvider.ANTHROPIC, parser.failure().get());
        }
        if (!parser.finished() || exitCode != 0) {
            log.warn("Claude-CLI endete mit Exit {}; stderr: {}", exitCode, stderr(workDir));
            return new ProviderUnavailableException(ChatProvider.ANTHROPIC,
                    "Die Claude-CLI wurde unerwartet beendet (Exit " + exitCode + ")");
        }
        return null;
    }

    /** Leere Werte als {@code --x=}: Java verwirft leere Argumente unter Windows stillschweigend. */
    private static List<String> arguments(ClaudeCliChatOptions options, boolean withTools) {
        List<String> args = new ArrayList<>(List.of("-p",
                "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--system-prompt-file", SYSTEM_PROMPT_FILE,
                "--tools=", "--setting-sources=", "--strict-mcp-config", "--disable-slash-commands",
                "--no-session-persistence"));
        if (options.getModel() != null) {
            args.addAll(List.of("--model", options.getModel()));
        }
        if (options.thinking() != null) {
            args.addAll(List.of("--effort", options.thinking() ? "high" : "low"));
        }
        if (withTools) {
            args.addAll(List.of("--mcp-config", MCP_CONFIG_FILE,
                    "--allowedTools", "mcp__" + ClaudeToolBridge.SERVER_NAME));
        }
        return args;
    }

    private String mcpConfig(String token) {
        String url = "http://127.0.0.1:" + serverPort.getAsInt() + ClaudeToolBridge.MCP_PATH;
        return JSON.writeValueAsString(Map.of("mcpServers", Map.of(ClaudeToolBridge.SERVER_NAME, Map.of(
                "type", "http",
                "url", url,
                "headers", Map.of("Authorization", "Bearer " + token)))));
    }

    static String systemPrompt(List<Message> messages) {
        return messages.stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM)
                .map(Message::getText)
                .collect(Collectors.joining("\n\n"));
    }

    /** Die letzte Nachricht ist die aktuelle Frage (samt RAG-Kontext); frühere Turns stehen als Verlauf davor. */
    static String userPrompt(List<Message> messages) {
        List<Message> turns = messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                .filter(m -> m.getText() != null && !m.getText().isBlank())
                .toList();
        if (turns.isEmpty()) {
            return "";
        }
        String current = turns.getLast().getText();
        if (turns.size() == 1) {
            return current;
        }
        String history = turns.subList(0, turns.size() - 1).stream()
                .map(m -> m.getMessageType() == MessageType.USER
                        ? "<user>\n" + m.getText() + "\n</user>"
                        : "<assistant>\n" + m.getText() + "\n</assistant>")
                .collect(Collectors.joining("\n"));
        return "Bisheriger Verlauf dieses Gesprächs:\n<conversation_history>\n" + history
                + "\n</conversation_history>\n\nAktuelle Nachricht, auf die du antwortest:\n" + current;
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String stderr(Path workDir) {
        try {
            String text = Files.readString(workDir.resolve(STDERR_FILE));
            return text.length() > 500 ? text.substring(text.length() - 500) : text;
        } catch (IOException e) {
            return "(nicht lesbar: " + e + ")";
        }
    }

    /** Läuft auch nach einem Abbruch, der diesen Thread unterbricht: das Verzeichnis enthält das Token des Laufs. */
    private static void cleanUp(Process process, Path workDir) {
        boolean interrupted = Thread.interrupted();
        if (process != null && process.isAlive()) {
            kill(process);
            try {
                process.waitFor(5, TimeUnit.SECONDS); // unter Windows sind die Dateien sonst noch gesperrt
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        try {
            if (workDir != null) {
                FileSystemUtils.deleteRecursively(workDir);
            }
        } catch (IOException e) {
            log.warn("Arbeitsverzeichnis {} der Claude-CLI nicht entfernt", workDir, e);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
