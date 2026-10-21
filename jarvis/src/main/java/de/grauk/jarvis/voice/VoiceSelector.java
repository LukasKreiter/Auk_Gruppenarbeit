package de.grauk.jarvis.voice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import de.grauk.jarvis.settings.SettingsService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wählt die Piper-Stimme: die in den Einstellungen für die Sprache hinterlegte Stimme ({@code voiceDe}/{@code voiceEn},
 * Name einer {@code <name>.onnx} im Ordner des Default-Modells), sonst das konfigurierte Default-Modell.
 */
@Component
public class VoiceSelector {

    /** Nur Dateinamen ohne Pfadanteile, z. B. {@code en_US-lessac-high}. */
    private static final Pattern VOICE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    private final String model;
    private final String config;
    private final int speaker;
    private final SettingsService settings;

    public VoiceSelector(
            @Value("${jarvis.voice.piper.model}") String model,
            @Value("${jarvis.voice.piper.config}") String config,
            @Value("${jarvis.voice.piper.speaker}") int speaker,
            SettingsService settings) {

        this.model = model;
        this.config = config;
        this.speaker = speaker;
        this.settings = settings;
    }

    /** @param language erkannte Sprache der Antwort ({@code "de"}, {@code "en"}, …); {@code null} = Deutsch */
    public Voice select(String language) {
        String name = settings.getVoiceFor(language);
        if (name == null || name.isBlank()) {
            return new Voice(model, config, speaker);
        }
        if (!VOICE_NAME.matcher(name).matches() || name.contains("..")) {
            throw new VoiceException("Ungültiger Stimmenname '" + name + "' in den Einstellungen.");
        }
        Path dir = Path.of(model).toAbsolutePath().getParent();
        Path voiceModel = dir.resolve(name.endsWith(".onnx") ? name : name + ".onnx");
        if (!Files.isRegularFile(voiceModel)) {
            throw new VoiceException("Stimme '" + name + "' nicht gefunden (erwartet: " + voiceModel + ").");
        }
        return new Voice(voiceModel.toString(), voiceModel + ".json", speaker);
    }

    public record Voice(
            String model,
            String config,
            int speaker
    ) {
    }
}
