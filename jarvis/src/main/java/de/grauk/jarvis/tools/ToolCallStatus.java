package de.grauk.jarvis.tools;

/** Ausgang eines Tool-Aufrufs; wird als {@code tool_call.status} gespeichert. */
public enum ToolCallStatus {
    /** Tool lief und lieferte ein Ergebnis. */
    OK,
    /** Tool warf eine Exception; das Ergebnis ist die Fehlermeldung an das Modell. */
    FAILED,
    /** Nicht ausgeführt: vom Nutzer abgelehnt, Bestätigung abgelaufen oder nicht möglich. */
    NOT_EXECUTED
}
