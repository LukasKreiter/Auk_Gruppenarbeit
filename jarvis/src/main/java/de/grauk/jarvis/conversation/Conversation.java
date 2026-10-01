package de.grauk.jarvis.conversation;

import org.springframework.web.reactive.function.client.WebClient;
import java.util.ArrayList;
import java.util.List;

public class Conversation {

    private List<Message> messages = new ArrayList<>();
    private AudioRecorder audioRecorder;

    public void startVoiceInput() throws Exception {
        this.audioRecorder = new AudioRecorder();
        this.audioRecorder.startRecording();
    }

    public Message stopVoiceInputAndProcess(WebClient.Builder webClientBuilder) {
        if (this.audioRecorder == null) {
            throw new IllegalStateException("Keine Aufnahme aktiv!");
        }

        // 1. Aufnahme stoppen und RAM-Bytes holen
        byte[] audioBytes = this.audioRecorder.stopRecording();
        this.audioRecorder = null;

        // 2. Message über Python STT erstellen
        Message audioMessage = Message.createFromAudioBytes(audioBytes, webClientBuilder);

        // 3. Zur Conversation hinzufügen
        this.messages.add(audioMessage);

        return audioMessage;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void addMessage(Message message) {
        this.messages.add(message);
    }
}