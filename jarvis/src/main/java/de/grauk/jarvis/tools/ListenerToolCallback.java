package de.grauk.jarvis.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Meldet Aufruf und Ergebnis an den {@link ToolInvocationListener} und schaltet ggf. das {@link ConfirmationGate} vor. */
class ListenerToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ListenerToolCallback.class);

    private final ToolCallback delegate;
    private final boolean requiresConfirmation;
    private final ConfirmationGate gate;

    ListenerToolCallback(ToolCallback delegate, boolean requiresConfirmation, ConfirmationGate gate) {
        this.delegate = delegate;
        this.requiresConfirmation = requiresConfirmation;
        this.gate = gate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        ToolInvocationListener listener = toolContext != null
                && toolContext.getContext().get(ToolInvocationListener.CONTEXT_KEY) instanceof ToolInvocationListener l
                ? l : null;
        String result;
        ToolCallStatus status = ToolCallStatus.OK;
        try {
            if (listener != null) {
                notifyCalled(listener, name, toolInput);
            }
            if (requiresConfirmation) {
                ConfirmationGate.Outcome outcome = gate.execute(name, toolInput, listener,
                        () -> delegate.call(toolInput, toolContext));
                result = outcome.text();
                status = outcome.executed() ? ToolCallStatus.OK : ToolCallStatus.NOT_EXECUTED;
            } else {
                result = delegate.call(toolInput, toolContext);
            }
        } catch (RuntimeException e) {
            log.warn("Tool {} fehlgeschlagen", name, e);
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            result = "Fehler bei Tool '" + name + "': " + message;
            status = ToolCallStatus.FAILED;
        }
        if (listener != null) {
            try {
                listener.onToolCompleted(name, toolInput, result, status);
            } catch (RuntimeException e) {
                log.warn("Listener.onToolCompleted für Tool {} fehlgeschlagen", name, e);
            }
        }
        return result;
    }

    private static void notifyCalled(ToolInvocationListener listener, String name, String toolInput) {
        try {
            listener.onToolCalled(name, toolInput);
        } catch (RuntimeException e) {
            log.warn("Listener.onToolCalled für Tool {} fehlgeschlagen", name, e);
        }
    }
}
