package de.grauk.jarvis.settings;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import de.grauk.jarvis.assistant.AssistantProperties;
import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ChatProviderRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Persistente Einstellungen des Assistenten (Singleton-Zeile in {@code assistant_settings}).
 * {@link #current()} und {@link SettingsSnapshot} sind der Vertrag, den assistant/ pro Anfrage liest;
 * der Snapshot wird im Speicher gehalten, damit nicht jede Anfrage die DB trifft. Der Cache wird nur nach einem
 * erfolgreichen Commit gesetzt – nach einem Rollback liefert {@link #current()} nie ungespeicherte Werte.
 */
@Service
public class SettingsService {

    public static final String DEFAULT_SYSTEM_PROMPT = """
            Du bist Jarvis, ein hilfsbereiter persönlicher Assistent. Antworte in der Sprache der Frage \
            (Deutsch oder Englisch), knapp und gut vorlesbar – ohne Tabellen oder Codeblöcke, \
            außer der Nutzer bittet ausdrücklich darum.""";

    public static final double DEFAULT_TEMPERATURE = 0.7;
    static final int MAX_SYSTEM_PROMPT_LENGTH = 4000;

    public record SettingsSnapshot(ChatProvider provider, String model, String systemPrompt,
                                   double temperature, boolean thinkMode) {}

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final AssistantSettingsRepository repository;
    private final AssistantProperties properties;
    // lazy: ChatProviderRegistry hängt selbst vom SettingsService ab
    private final ObjectProvider<ChatProviderRegistry> registry;
    private final String defaultOllamaModel;

    /** Serialisiert Schreiber inklusive Commit und das erstmalige Laden, damit der Cache nie veraltet überschrieben wird. */
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile SettingsSnapshot cached;
    /** Spring-Proxy auf sich selbst, damit interne Aufrufe {@code @Transactional} beachten (ohne Spring: this). */
    private SettingsService self = this;

    public SettingsService(AssistantSettingsRepository repository, AssistantProperties properties,
                           ObjectProvider<ChatProviderRegistry> registry,
                           @Value("${spring.ai.ollama.chat.options.model:qwen3:8b}") String defaultOllamaModel) {
        this.repository = repository;
        this.properties = properties;
        this.registry = registry;
        this.defaultOllamaModel = defaultOllamaModel;
    }

    @Autowired
    void setSelf(@Lazy SettingsService self) {
        this.self = self;
    }

    /** Aktuelle Einstellungen für eine Anfrage. Legt die Zeile beim ersten Zugriff mit den Defaults an. */
    public SettingsSnapshot current() {
        SettingsSnapshot snapshot = cached;
        if (snapshot != null) {
            return snapshot;
        }
        writeLock.lock();
        try {
            snapshot = cached;
            if (snapshot == null) {
                // getSettings() ist eine eigene Transaktion und ist bei Rückkehr committed
                snapshot = toSnapshot(self.getSettings());
                publishAfterCommit(snapshot);
            }
            return snapshot;
        } finally {
            writeLock.unlock();
        }
    }

    /** Die Entity, z. B. als Formular-Objekt für die Einstellungsseite. Setzt den Cache nicht bei bloßem Lesen. */
    @Transactional
    public AssistantSettings getSettings() {
        return repository.findById(AssistantSettings.SINGLETON_ID).orElseGet(() -> {
            AssistantSettings created = repository.save(defaults());
            publishAfterCommit(toSnapshot(created));
            return created;
        });
    }

    /**
     * Prüft und speichert die Werte aus dem Formular. Die Modellliste (Netzwerk) wird vor Lock und Transaktion geholt;
     * der Cache wird erst nach dem Commit aktualisiert.
     */
    public AssistantSettings update(AssistantSettings changes) {
        validateBasics(changes);
        List<String> models = getAvailableModels(changes.getProvider());
        writeLock.lock();
        try {
            validateModel(changes, models, self.getSettings());
            return self.store(changes);
        } finally {
            writeLock.unlock();
        }
    }

    /** Transaktionaler Schreibteil von {@link #update(AssistantSettings)}; nicht direkt aufrufen. */
    @Transactional
    public AssistantSettings store(AssistantSettings changes) {
        AssistantSettings settings = repository.findById(AssistantSettings.SINGLETON_ID)
                .orElseGet(this::defaults);
        settings.copyFrom(changes);
        AssistantSettings saved = repository.save(settings);
        publishAfterCommit(toSnapshot(saved));
        return saved;
    }

    /** Ändert nur die Felder des Snapshots; Stimmen und TTS bleiben erhalten. */
    public void update(SettingsSnapshot snapshot) {
        AssistantSettings changes = new AssistantSettings(snapshot.provider(), snapshot.model(),
                snapshot.systemPrompt(), snapshot.temperature(), snapshot.thinkMode(), null, null, true);
        AssistantSettings existing = self.getSettings();
        changes.setVoiceDe(existing.getVoiceDe());
        changes.setVoiceEn(existing.getVoiceEn());
        changes.setTtsEnabled(existing.isTtsEnabled());
        update(changes);
    }

    public AssistantSettings resetToDefaults() {
        return update(defaults());
    }

    /** Modelle für das Dropdown des aktiven Providers (Ollama live, Claude aus der Konfiguration). */
    public List<String> getAvailableModels() {
        return getAvailableModels(current().provider());
    }

    public List<String> getAvailableModels(ChatProvider provider) {
        return registry.getObject().models(provider);
    }

    /** Stimme für die Sprache ({@code "de"}/{@code "en"}, auch {@code "de-AT"} o. Ä.); {@code null} = Default des voice-service. */
    public String getVoiceFor(String language) {
        AssistantSettings settings = self.getSettings();
        if (language != null && language.toLowerCase().startsWith("en")) {
            return settings.getVoiceEn();
        }
        return settings.getVoiceDe();
    }

    public boolean isTtsEnabled() {
        return self.getSettings().isTtsEnabled();
    }

    /**
     * Konfigurierter Ersatz, wenn die Modellliste des Providers nicht abrufbar ist: bei Ollama das Default-Modell,
     * bei Claude das erste aus {@code jarvis.assistant.anthropic-models}; sonst {@code null}.
     */
    public String fallbackModel(ChatProvider provider) {
        if (provider == ChatProvider.ANTHROPIC) {
            return properties.anthropicModels().isEmpty() ? null : properties.anthropicModels().getFirst();
        }
        return defaultOllamaModel;
    }

    AssistantSettings defaults() {
        ChatProvider provider = properties.defaultProvider();
        String model = provider == ChatProvider.ANTHROPIC && !properties.anthropicModels().isEmpty()
                ? properties.anthropicModels().getFirst()
                : defaultOllamaModel;
        return new AssistantSettings(provider, model, DEFAULT_SYSTEM_PROMPT, DEFAULT_TEMPERATURE,
                false, null, null, true);
    }

    private void validateBasics(AssistantSettings s) {
        if (s.getProvider() == null) {
            throw new IllegalArgumentException("Provider fehlt");
        }
        if (s.getModel() == null || s.getModel().isBlank()) {
            throw new IllegalArgumentException("Modell fehlt");
        }
        if (s.getSystemPrompt() == null || s.getSystemPrompt().isBlank()) {
            throw new IllegalArgumentException("System-Prompt darf nicht leer sein");
        }
        if (s.getSystemPrompt().length() > MAX_SYSTEM_PROMPT_LENGTH) {
            throw new IllegalArgumentException("System-Prompt ist länger als " + MAX_SYSTEM_PROMPT_LENGTH + " Zeichen");
        }
        // Claude erlaubt nur 0–1, Ollama 0–2
        double max = s.getProvider() == ChatProvider.ANTHROPIC ? 1.0 : 2.0;
        if (Double.isNaN(s.getTemperature()) || s.getTemperature() < 0.0 || s.getTemperature() > max) {
            throw new IllegalArgumentException("Temperatur muss zwischen 0.0 und " + max + " liegen");
        }
    }

    private void validateModel(AssistantSettings s, List<String> models, AssistantSettings stored) {
        if (models.isEmpty()) {
            // Liste nicht abrufbar: nur erlauben, wenn Provider und Modell unverändert bleiben
            if (s.getProvider() == stored.getProvider() && s.getModel().equals(stored.getModel())) {
                return;
            }
            log.warn("Modellliste von {} nicht abrufbar – Änderung auf {}/{} abgelehnt", s.getProvider(),
                    s.getProvider(), s.getModel());
            throw new IllegalArgumentException("Modellliste von " + s.getProvider()
                    + " nicht abrufbar – Modell kann nicht geprüft werden");
        }
        if (!models.contains(s.getModel())) {
            throw new IllegalArgumentException("Modell '" + s.getModel() + "' ist bei " + s.getProvider() + " nicht vorhanden");
        }
    }

    /** Setzt den Cache nach erfolgreichem Commit (ohne aktive Transaktion sofort); bei Rollback bleibt er unberührt. */
    private void publishAfterCommit(SettingsSnapshot snapshot) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cached = snapshot;
                }
            });
        } else {
            cached = snapshot;
        }
    }

    private static SettingsSnapshot toSnapshot(AssistantSettings s) {
        return new SettingsSnapshot(s.getProvider(), s.getModel(), s.getSystemPrompt(),
                s.getTemperature(), s.isThinkMode());
    }
}
