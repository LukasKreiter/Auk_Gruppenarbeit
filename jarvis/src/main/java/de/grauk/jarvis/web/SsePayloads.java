package de.grauk.jarvis.web;

import java.util.List;

import de.grauk.jarvis.assistant.AssistantEvent;
import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.web.Dtos.ToolCallDto;

/** JSON-Nutzdaten der SSE-Events des Chats (Eventname = {@link AssistantEvent#sseName()}). */
public final class SsePayloads {

    private SsePayloads() {
    }

    public record TokenPayload(String text) {}

    public record ToolPayload(String toolName, String arguments) {}

    public record ConfirmPayload(String requestId, String toolName, String arguments) {}

    public record WarningPayload(String message) {}

    /** {@code messageId}: Id der gespeicherten Assistenten-Nachricht. */
    public record DonePayload(Long messageId, String text, ChatProvider provider, String model, long llmMs,
                              List<ToolCallDto> toolCalls) {}

    /** {@code toolCalls}: vor dem Fehler schon ausgeführte Tools (sonst leer). */
    public record ErrorPayload(String message, List<ToolCallDto> toolCalls) {
        public ErrorPayload(String message) {
            this(message, List.of());
        }
    }

    /** Nutzdaten für Events, die direkt aus dem Stream kommen; {@code Done} braucht die gespeicherte Id und wird separat gebaut. */
    static Object of(AssistantEvent event) {
        return switch (event) {
            case AssistantEvent.Token t -> new TokenPayload(t.text());
            case AssistantEvent.ToolCalled t -> new ToolPayload(t.toolName(), t.argumentsJson());
            case AssistantEvent.ConfirmationRequired c ->
                    new ConfirmPayload(c.requestId().toString(), c.toolName(), c.argumentsJson());
            case AssistantEvent.Warning w -> new WarningPayload(w.message());
            case AssistantEvent.Error e -> new ErrorPayload(e.userMessage());
            case AssistantEvent.Done d -> throw new IllegalArgumentException("Done braucht die gespeicherte Nachricht");
        };
    }
}
