package de.grauk.jarvis.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Macht die Tools eines laufenden CLI-Prozesses für den MCP-Endpunkt erreichbar. Jeder Lauf bekommt ein eigenes,
 * zufälliges Token; es gilt nur solange der Prozess läuft. So bleiben freigegebene Tools, Listener und
 * ConfirmationGate pro Anfrage dieselben wie beim Ollama-Pfad (ADR 0006).
 */
@Component
public class ClaudeToolBridge {

    /** Pfad des MCP-Endpunkts; die Claude-CLI ruft ihn als lokalen Streamable-HTTP-Server auf. */
    public static final String MCP_PATH = "/internal/claude-mcp";

    /** Name des MCP-Servers; die CLI stellt den Tools {@code mcp__jarvis__} voran. */
    static final String SERVER_NAME = "jarvis";

    /** @param tools Toolname → Callback */
    public record ToolSession(Map<String, ToolCallback> tools, ToolContext context) {}

    private final Map<String, ToolSession> sessions = new ConcurrentHashMap<>();

    /** @return Token für den Bearer-Header; mit {@link #close} wieder entfernen. */
    String open(List<ToolCallback> tools, Map<String, Object> toolContext) {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        tools.forEach(tool -> byName.put(tool.getToolDefinition().name(), tool));
        String token = UUID.randomUUID().toString(); // SecureRandom, 122 Bit
        sessions.put(token, new ToolSession(byName, new ToolContext(toolContext)));
        return token;
    }

    void close(String token) {
        sessions.remove(token);
    }

    public Optional<ToolSession> find(String token) {
        return Optional.ofNullable(sessions.get(token));
    }
}
