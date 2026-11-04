package de.grauk.jarvis.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.grauk.jarvis.assistant.ClaudeToolBridge;
import de.grauk.jarvis.assistant.ClaudeToolBridge.ToolSession;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lokaler MCP-Server (Streamable HTTP, JSON-Antworten) für die Claude-CLI. Bewusst nur die vier Methoden, die ein
 * Tool-Server braucht, statt des MCP-Starters: Tool-Liste und Listener gelten hier pro Anfrage, nicht pro Anwendung
 * (ADR 0006). Zugriff nur mit dem Bearer-Token des gerade laufenden CLI-Prozesses.
 */
@RestController
public class ClaudeMcpController {

    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final String BEARER = "Bearer ";

    private final ClaudeToolBridge bridge;
    private final JsonMapper json;

    public ClaudeMcpController(ClaudeToolBridge bridge, JsonMapper json) {
        this.bridge = bridge;
        this.json = json;
    }

    @PostMapping(path = ClaudeToolBridge.MCP_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> handle(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody JsonNode request) {
        ToolSession session = authorization != null && authorization.startsWith(BEARER)
                ? bridge.find(authorization.substring(BEARER.length())).orElse(null)
                : null;
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!request.hasNonNull("id")) {
            return ResponseEntity.accepted().build(); // Notification, z. B. notifications/initialized
        }
        JsonNode id = request.get("id");
        JsonNode params = request.path("params");
        return ResponseEntity.ok(switch (request.path("method").asString("")) {
            case "initialize" -> result(id, initialize(params));
            case "ping" -> result(id, Map.of());
            case "tools/list" -> result(id, Map.of("tools", toolList(session)));
            case "tools/call" -> callTool(id, params, session);
            default -> error(id, METHOD_NOT_FOUND, "Methode nicht unterstützt");
        });
    }

    /** Der genutzte Teil des Protokolls ist in allen Versionen gleich: die Version des Clients wird übernommen. */
    private static Map<String, Object> initialize(JsonNode params) {
        return Map.of("protocolVersion", params.path("protocolVersion").asString("2025-06-18"),
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "jarvis", "version", "1.0"));
    }

    private List<Map<String, Object>> toolList(ToolSession session) {
        return session.tools().values().stream().map(tool -> {
            ToolDefinition definition = tool.getToolDefinition();
            return Map.of("name", definition.name(), "description", definition.description(),
                    "inputSchema", json.readTree(definition.inputSchema()));
        }).toList();
    }

    /** Blockiert bei bestätigungspflichtigen Tools, bis der Nutzer im Chat entschieden hat (ConfirmationGate). */
    private static Map<String, Object> callTool(JsonNode id, JsonNode params, ToolSession session) {
        ToolCallback tool = session.tools().get(params.path("name").asString(""));
        if (tool == null) {
            return error(id, INVALID_PARAMS, "Unbekanntes Tool");
        }
        JsonNode arguments = params.path("arguments");
        String text = tool.call(arguments.isObject() ? arguments.toString() : "{}", session.context());
        return result(id, Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", false));
    }

    private static Map<String, Object> result(JsonNode id, Object result) {
        return envelope(id, "result", result);
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        return envelope(id, "error", Map.of("code", code, "message", message));
    }

    private static Map<String, Object> envelope(JsonNode id, String key, Object value) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put(key, value);
        return response;
    }
}
