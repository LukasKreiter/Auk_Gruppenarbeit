package de.grauk.jarvis.web;

/** Für die Konversation läuft bereits eine Antwort (Chat-Stream); wird als 409 gemeldet. */
public class StreamActiveException extends RuntimeException {

    public StreamActiveException(String message) {
        super(message);
    }
}
