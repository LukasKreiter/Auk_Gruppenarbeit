package de.grauk.jarvis.voice;

/** Sprachausgabe fehlgeschlagen oder ausgelastet; Meldung ist für Nutzer lesbar, Details stehen in der Ursache. */
public class VoiceException extends RuntimeException {

    public VoiceException(String message, Throwable cause) {
        super(message, cause);
    }

    public VoiceException(String message) {
        super(message);
    }
}
