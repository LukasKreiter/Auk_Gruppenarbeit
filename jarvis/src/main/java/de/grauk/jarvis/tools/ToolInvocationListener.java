package de.grauk.jarvis.tools;

import java.util.UUID;

/**
 * Rückkanal von Tool-Aufrufen in die laufende Anfrage. AssistantService legt pro Anfrage einen Listener
 * unter {@link #CONTEXT_KEY} in den Spring-AI-ToolContext; die Wrapper aus ToolRegistry/ConfirmationGate
 * melden darüber Aufrufe, Bestätigungsanfragen und Ergebnisse.
 */
public interface ToolInvocationListener {

    String CONTEXT_KEY = "jarvis.toolInvocationListener";

    void onToolCalled(String toolName, String argumentsJson);

    void onConfirmationRequired(UUID requestId, String toolName, String argumentsJson);

    void onToolCompleted(String toolName, String argumentsJson, String result);
}
