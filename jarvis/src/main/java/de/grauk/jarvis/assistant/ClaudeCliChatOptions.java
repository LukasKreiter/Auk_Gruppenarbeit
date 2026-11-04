package de.grauk.jarvis.assistant;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Optionen eines CLI-Laufs. Die CLI kennt nur Modell und Denkaufwand; Temperatur wird nicht übergeben (ADR 0006).
 * Die Tools laufen über die geerbten ToolCallbacks in die {@link ClaudeToolBridge}.
 */
public final class ClaudeCliChatOptions extends DefaultToolCallingChatOptions {

    private final Boolean thinking;

    private ClaudeCliChatOptions(List<ToolCallback> toolCallbacks, Map<String, Object> toolContext, String model,
                                 Double temperature, Boolean thinking) {
        super(toolCallbacks == null ? List.of() : toolCallbacks, toolContext == null ? Map.of() : toolContext, model,
                null, null, null, null, temperature, null, null);
        this.thinking = thinking;
    }

    /** {@code null}: Vorgabe der CLI. */
    Boolean thinking() {
        return thinking;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Builder mutate() {
        return builder().model(getModel()).toolCallbacks(getToolCallbacks()).toolContext(getToolContext())
                .thinking(thinking);
    }

    public static final class Builder extends DefaultToolCallingChatOptions.Builder<Builder> {

        private Boolean thinking;

        public Builder thinking(Boolean thinking) {
            this.thinking = thinking;
            return this;
        }

        @Override
        public Builder combineWith(ChatOptions.Builder<?> other) {
            super.combineWith(other);
            if (other instanceof Builder b && b.thinking != null) {
                this.thinking = b.thinking;
            }
            return this;
        }

        @Override
        public ClaudeCliChatOptions build() {
            return new ClaudeCliChatOptions(toolCallbacks, toolContext, model, temperature, thinking);
        }
    }
}
