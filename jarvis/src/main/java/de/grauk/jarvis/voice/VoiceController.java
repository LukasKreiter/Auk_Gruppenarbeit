package de.grauk.jarvis.voice;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceServiceClient voiceServiceClient;

    public VoiceController(
            VoiceServiceClient voiceServiceClient) {

        this.voiceServiceClient = voiceServiceClient;
    }

    @GetMapping(
            produces = "audio/wav"
    )
    public ResponseEntity<byte[]> synthesize(
            @RequestParam String text) {

        byte[] audio = voiceServiceClient.synthesize(text);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .contentLength(audio.length)
                .body(audio);
    }
}

