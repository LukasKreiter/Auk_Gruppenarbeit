package de.grauk.jarvis.assistant;

/** Wer das Chat-Modell ausführt. Wird pro Anfrage aus den Einstellungen gelesen (ADR 0002). */
public enum ChatProvider {
    /** Ollama auf dem LLM-Server. */
    OLLAMA,
    /** Claude über die angemeldete Claude-Code-CLI (Cloud-Modus, ADR 0006). */
    ANTHROPIC
}
