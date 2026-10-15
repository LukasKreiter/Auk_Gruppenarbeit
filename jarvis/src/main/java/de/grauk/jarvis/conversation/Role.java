package de.grauk.jarvis.conversation;

/** Absender einer {@link Message}. System- und Tool-Nachrichten werden nicht gespeichert (Tool-Aufrufe: {@link ToolCall}). */
public enum Role {
    USER,
    ASSISTANT
}
