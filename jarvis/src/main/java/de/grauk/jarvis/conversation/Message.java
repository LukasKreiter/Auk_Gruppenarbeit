package de.grauk.jarvis.conversation;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

public class Message {

    private String content;
    private String sender;

    public Message() {
    }

    public Message(String content, String sender) {
        this.content = content;
        this.sender = sender;
    }

    // Wandelt Audiodaten direkt über das Python-Sidecar in Text um
    public static Message createFromAudioBytes(byte[] audioBytes, WebClient.Builder webClientBuilder) {
        WebClient webClient = webClientBuilder.baseUrl("http://127.0.0.1:8090").build();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource fileResource = new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return "speech.wav";
            }
        };
        body.add("file", fileResource);

        TranscriptionResponse response = webClient.post()
                .uri("/transcribe")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(TranscriptionResponse.class)
                .block();

        String recognizedText = (response != null) ? response.getText() : "";
        return new Message(recognizedText, "USER");
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    private static class TranscriptionResponse {
        private String text;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }
}