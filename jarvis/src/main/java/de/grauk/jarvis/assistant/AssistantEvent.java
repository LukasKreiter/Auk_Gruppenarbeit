package de.grauk.jarvis.assistant;

import java.util.List;
import java.util.UUID;

/**
 * Ereignisse einer Anfrage; web/ streamt sie als SSE.
 * Eventnamen (Vertrag mit dem Frontend): token, tool, confirm, warning, done, error – siehe {@link #sseName()}.
 */
public sealed interface AssistantEvent {

    /** SSE-Eventname für dieses Ereignis. */
    String sseName();

    record Token(String text) implements AssistantEvent {
        public String sseName() { return "token"; }
    }

    record ToolCalled(String toolName, String argumentsJson) implements AssistantEvent {
        public String sseName() { return "tool"; }
    }

    record ConfirmationRequired(UUID requestId, String toolName, String argumentsJson) implements AssistantEvent {
        public String sseName() { return "confirm"; }
    }

    /** Nicht fatal, z. B. RAG-Kontext nicht verfügbar, weil der LLM-Server weg ist. */
    record Warning(String message) implements AssistantEvent {
        public String sseName() { return "warning"; }
    }

    record Done(String fullText, ChatProvider provider, String model,
                long llmMillis, List<ToolCallRecord> toolCalls) implements AssistantEvent {
        public Done {
            toolCalls = List.copyOf(toolCalls);
        }

        public String sseName() { return "done"; }
    }

    /** Anfrage abgebrochen; {@code userMessage} ist für den Nutzer formuliert. */
    record Error(String userMessage) implements AssistantEvent {
        public String sseName() { return "error"; }
    }
}
