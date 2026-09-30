package de.grauk.jarvis.settings;

import de.grauk.jarvis.assistant.ChatProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Minimaler Platzhalter aus dem KI-Kern (Brenner), damit die Chat-Pipeline lauffähig ist.
 * TODO Hiebler: durch die persistente Variante über AssistantSettings (assistant_settings) ersetzen.
 * {@link #current()} und {@link SettingsSnapshot} sind der Vertrag, den assistant/ nutzt.
 */
@Service
public class SettingsService {

    public static final String DEFAULT_SYSTEM_PROMPT = """
            Du bist Jarvis, ein hilfsbereiter persönlicher Assistent. Antworte in der Sprache der Frage \
            (Deutsch oder Englisch), knapp und gut vorlesbar – ohne Tabellen oder Codeblöcke, \
            außer der Nutzer bittet ausdrücklich darum.""";

    public record SettingsSnapshot(ChatProvider provider, String model, String systemPrompt,
                                   double temperature, boolean thinkMode) {}

    private volatile SettingsSnapshot current;

    public SettingsService(@Value("${jarvis.assistant.default-provider:OLLAMA}") ChatProvider defaultProvider,
                           @Value("${spring.ai.ollama.chat.options.model:qwen3:8b}") String ollamaModel) {
        this.current = new SettingsSnapshot(defaultProvider, ollamaModel, DEFAULT_SYSTEM_PROMPT, 0.7, false);
    }

    public SettingsSnapshot current() {
        return current;
    }

    public void update(SettingsSnapshot settings) {
        this.current = settings;
    }
}
