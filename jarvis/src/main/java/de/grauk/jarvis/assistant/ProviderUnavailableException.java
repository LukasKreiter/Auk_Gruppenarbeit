package de.grauk.jarvis.assistant;

/** Der gewählte Provider ist nicht erreichbar oder nicht konfiguriert. Kein automatischer Fallback (ADR 0005). */
public class ProviderUnavailableException extends RuntimeException {

    private final ChatProvider provider;

    public ProviderUnavailableException(ChatProvider provider, String message) {
        super(message);
        this.provider = provider;
    }

    public ProviderUnavailableException(ChatProvider provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public ChatProvider provider() {
        return provider;
    }
}
