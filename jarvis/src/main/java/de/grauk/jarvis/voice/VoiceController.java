package de.grauk.jarvis.voice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import de.grauk.jarvis.conversation.ConversationService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceServiceClient voiceServiceClient;

    private final ConversationService conversations;

    public VoiceController(
            VoiceServiceClient voiceServiceClient,
            ConversationService conversations) {

        this.voiceServiceClient = voiceServiceClient;
        this.conversations = conversations;
    }

    /** POST statt GET: zustandsändernd und teuer, deshalb unter dem Origin-Check des LocalOnlyFilter. */
    @PostMapping(
            path = "/synthesize",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "audio/wav"
    )
    public ResponseEntity<byte[]> synthesize(
            @Valid @RequestBody SynthesizeRequest request) {

        long start = System.nanoTime();
        byte[] audio = voiceServiceClient.synthesize(request.text(), request.language());

        if (request.messageId() != null) {
            conversations.recordTtsMillis(request.messageId(), (System.nanoTime() - start) / 1_000_000);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .contentLength(audio.length)
                .body(audio);
    }

    /** {@code messageId} optional: die TTS-Dauer wird dann an der Antwort-Nachricht gespeichert. */
    public record SynthesizeRequest(
            @NotBlank @Size(max = VoiceServiceClient.MAX_TEXT_LENGTH) String text,
            @Size(max = 10) String language,
            Long messageId
    ) {
    }
}
