package de.grauk.jarvis.assistant;

import de.grauk.jarvis.tools.ToolCallStatus;

/** Protokoll eines Tool-Aufrufs innerhalb einer Anfrage; conversation/ speichert es als tool_call. */
public record ToolCallRecord(String toolName, String argumentsJson, String result, ToolCallStatus status) {

    public ToolCallRecord(String toolName, String argumentsJson, String result) {
        this(toolName, argumentsJson, result, ToolCallStatus.OK);
    }
}
