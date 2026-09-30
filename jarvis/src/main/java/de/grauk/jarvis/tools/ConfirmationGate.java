package de.grauk.jarvis.tools;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Holt vor der Ausführung eines bestätigungspflichtigen Tools die Freigabe des Nutzers ein. */
@Component
public class ConfirmationGate {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationGate.class);

    private final ToolsProperties properties;
    private final Map<UUID, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    public ConfirmationGate(ToolsProperties properties) {
        this.properties = properties;
    }

    /**
     * Fragt über den Listener nach und blockiert bis Freigabe, Ablehnung oder Timeout. Nur bei Freigabe wird
     * {@code action} ausgeführt; sonst kommt ein Ergebnistext für das Modell zurück.
     */
    public String execute(String toolName, String argumentsJson, ToolInvocationListener listener,
                          Supplier<String> action) {
        if (listener == null) {
            log.warn("Tool {} braucht Bestätigung, aber es gibt keinen Listener – nicht ausgeführt", toolName);
            return "Das Tool '" + toolName + "' wurde nicht ausgeführt: Es ist keine Bestätigung beim Nutzer möglich.";
        }
        UUID requestId = UUID.randomUUID();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            listener.onConfirmationRequired(requestId, toolName, argumentsJson);
            boolean approved = future.get(properties.confirmationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!approved) {
                log.info("Tool {} vom Nutzer abgelehnt (requestId={})", toolName, requestId);
                return "Der Nutzer hat die Ausführung von '" + toolName + "' abgelehnt. Das Tool wurde nicht ausgeführt.";
            }
        } catch (TimeoutException e) {
            log.info("Bestätigung für Tool {} abgelaufen (requestId={})", toolName, requestId);
            return "Der Nutzer hat nicht rechtzeitig auf die Bestätigung für '" + toolName
                    + "' geantwortet. Das Tool wurde nicht ausgeführt.";
        } catch (InterruptedException e) {
            log.info("Bestätigung für Tool {} unterbrochen (requestId={})", toolName, requestId);
            Thread.currentThread().interrupt();
            return "Die Bestätigung für '" + toolName + "' wurde abgebrochen. Das Tool wurde nicht ausgeführt.";
        } catch (ExecutionException e) {
            // nur vom Compiler verlangt: das Future wird ausschließlich über complete(boolean) erfüllt
            log.warn("Bestätigung für {} fehlgeschlagen", toolName, e);
            return "Die Bestätigung für '" + toolName + "' ist fehlgeschlagen. Das Tool wurde nicht ausgeführt.";
        } finally {
            pending.remove(requestId);
        }
        return action.get();
    }

    /** Antwort des Nutzers; wirft {@link ConfirmationNotFoundException} bei unbekannter/abgelaufener ID. */
    public void resolve(UUID requestId, boolean approved) {
        CompletableFuture<Boolean> future = pending.remove(requestId);
        if (future == null) {
            throw new ConfirmationNotFoundException(requestId);
        }
        future.complete(approved);
    }
}
