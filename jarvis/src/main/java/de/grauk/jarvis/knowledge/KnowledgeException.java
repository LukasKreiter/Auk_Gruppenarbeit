package de.grauk.jarvis.knowledge;

/** Fehler in der Wissensverwaltung (z. B. Embedding fehlgeschlagen); Meldung ist für Nutzer lesbar. */
public class KnowledgeException extends RuntimeException {

    public KnowledgeException(String message, Throwable cause) {
        super(message, cause);
    }

    public KnowledgeException(String message) {
        super(message);
    }
}
