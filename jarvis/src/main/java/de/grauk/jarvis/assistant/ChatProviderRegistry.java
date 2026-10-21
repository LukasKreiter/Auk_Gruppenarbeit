package de.grauk.jarvis.assistant;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import de.grauk.jarvis.settings.SettingsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Hält je Provider einen ChatClient und kennt Verfügbarkeit und Modelllisten. Kein Fallback zwischen Providern (ADR 0005). */
@Service
public class ChatProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatProviderRegistry.class);
    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(2);
    private static final long CACHE_NANOS = Duration.ofSeconds(4).toNanos();

    private final ChatClients clients;
    private final SettingsService settings;
    private final AssistantProperties properties;
    private final ObjectProvider<OllamaApi> ollamaApi;
    private final String anthropicApiKey;

    private record OllamaProbe(Optional<List<String>> models, long takenAtNanos) {}

    private volatile OllamaProbe probe;

    public ChatProviderRegistry(ChatClients clients, SettingsService settings, AssistantProperties properties,
                                ObjectProvider<OllamaApi> ollamaApi,
                                @Value("${spring.ai.anthropic.api-key:}") String anthropicApiKey) {
        this.clients = clients;
        this.settings = settings;
        this.properties = properties;
        this.ollamaApi = ollamaApi;
        this.anthropicApiKey = anthropicApiKey;
    }

    /**
     * @throws ProviderUnavailableException wenn der Provider nicht konfiguriert ist (Claude ohne API-Key).
     *         Ob der LLM-Server läuft, prüft erst der Aufruf selbst; der Fehler wird dort gemeldet.
     */
    public ChatClient clientFor(ChatProvider provider) {
        if (provider == ChatProvider.ANTHROPIC && !anthropicKeyPresent()) {
            throw new ProviderUnavailableException(provider,
                    "Claude ist nicht konfiguriert – die Umgebungsvariable ANTHROPIC_API_KEY fehlt");
        }
        ChatClient client = clients.byProvider().get(provider);
        if (client == null) {
            throw new ProviderUnavailableException(provider, "Provider " + provider + " ist nicht verfügbar");
        }
        return client;
    }

    /** Ollama: /api/tags erreichbar (kurz gecacht, die Statusseite pollt); Claude: API-Key gesetzt. */
    public Map<ChatProvider, Boolean> available() {
        Map<ChatProvider, Boolean> result = new EnumMap<>(ChatProvider.class);
        result.put(ChatProvider.OLLAMA, ollamaModels().isPresent());
        result.put(ChatProvider.ANTHROPIC, anthropicKeyPresent() && clients.byProvider().containsKey(ChatProvider.ANTHROPIC));
        return result;
    }

    public ChatProvider active() {
        return settings.current().provider();
    }

    /** Ollama live, Claude aus der Konfiguration. Bei Ollama-Fehler leere Liste (geloggt), keine Exception. */
    public List<String> models(ChatProvider provider) {
        return switch (provider) {
            case OLLAMA -> ollamaModels().orElse(List.of());
            case ANTHROPIC -> properties.anthropicModels();
        };
    }

    private boolean anthropicKeyPresent() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }

    private Optional<List<String>> ollamaModels() {
        OllamaProbe cached = probe;
        if (cached != null && System.nanoTime() - cached.takenAtNanos() < CACHE_NANOS) {
            return cached.models();
        }
        Optional<List<String>> models = fetchOllamaModels();
        probe = new OllamaProbe(models, System.nanoTime());
        return models;
    }

    private Optional<List<String>> fetchOllamaModels() {
        OllamaApi api = ollamaApi.getIfAvailable();
        if (api == null) {
            return Optional.empty();
        }
        try {
            List<String> names = CompletableFuture.supplyAsync(() -> api.listModels().models().stream()
                            .map(OllamaApi.Model::name).toList())
                    .orTimeout(OLLAMA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            return Optional.of(names);
        } catch (RuntimeException e) {
            log.warn("Ollama nicht erreichbar (/api/tags): {}", e.toString());
            return Optional.empty();
        }
    }
}
