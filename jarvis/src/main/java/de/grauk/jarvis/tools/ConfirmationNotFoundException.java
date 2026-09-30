package de.grauk.jarvis.tools;

import java.util.UUID;

/** Bestätigungs-ID unbekannt oder abgelaufen; web/ übersetzt das in 404. */
public class ConfirmationNotFoundException extends RuntimeException {

    public ConfirmationNotFoundException(UUID requestId) {
        super("Keine offene Bestätigungsanfrage mit ID " + requestId);
    }
}
