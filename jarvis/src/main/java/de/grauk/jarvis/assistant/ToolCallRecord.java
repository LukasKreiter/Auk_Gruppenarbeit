package de.grauk.jarvis.assistant;

/** Protokoll eines Tool-Aufrufs innerhalb einer Anfrage; conversation/ speichert es als tool_call. */
public record ToolCallRecord(String toolName, String argumentsJson, String result) {
}
