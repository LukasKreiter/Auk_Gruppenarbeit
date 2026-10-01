package de.grauk.jarvis.voice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VoiceSelector {

    private final String model;
    private final String config;
    private final int speaker;

    public VoiceSelector(
            @Value("${voice.piper.model}") String model,
            @Value("${voice.piper.config}") String config,
            @Value("${voice.piper.speaker:0}") int speaker) {

        this.model = model;
        this.config = config;
        this.speaker = speaker;
    }

    public Voice select() {
        return new Voice(model, config, speaker);
    }

    public record Voice(
            String model,
            String config,
            int speaker
    ) {
    }
}

