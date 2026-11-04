package de.grauk.jarvis.assistant;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wertet die {@code stream-json}-Ausgabe eines CLI-Laufs zeilenweise aus; eine Instanz pro Lauf.
 * Antworttext kommt nur aus {@code text_delta}: Fehlertexte stecken in synthetischen {@code assistant}-Nachrichten
 * und landen so nie als Antwort im Chat.
 */
final class ClaudeStreamParser {

    private static final Logger log = LoggerFactory.getLogger(ClaudeStreamParser.class);
    private static final JsonMapper JSON = JsonMapper.shared();

    private boolean textSeen;
    private boolean separatorDue;
    private boolean finished;
    private String errorCode = "";
    private String failure;

    /** @return der neue Antworttext dieser Zeile, sonst leer. */
    String accept(String line) {
        JsonNode event;
        try {
            event = JSON.readTree(line);
        } catch (JacksonException e) {
            log.debug("Zeile der Claude-CLI ist kein JSON: {}", line);
            return "";
        }
        switch (event.path("type").asString("")) {
            case "stream_event" -> {
                return textOf(event.path("event"));
            }
            case "assistant" -> {
                if (event.hasNonNull("error")) {
                    errorCode = event.path("error").asString("");
                }
            }
            case "result" -> {
                finished = true;
                if (event.path("is_error").asBoolean(false)) {
                    failure = failureMessage(event);
                }
            }
            default -> {
                // Status- und Init-Ereignisse sind für den Chat ohne Bedeutung
            }
        }
        return "";
    }

    /** Die CLI hat ihr {@code result}-Ereignis geschrieben; fehlt es, ist der Prozess abgestürzt. */
    boolean finished() {
        return finished;
    }

    /** Text für den Nutzer, wenn der Lauf mit Fehler endete. */
    Optional<String> failure() {
        return Optional.ofNullable(failure);
    }

    private String textOf(JsonNode streamEvent) {
        String type = streamEvent.path("type").asString("");
        if ("message_start".equals(type)) {
            // neue Modellrunde nach einem Tool-Aufruf: Text beider Runden nicht direkt aneinanderkleben
            separatorDue = textSeen;
            return "";
        }
        JsonNode delta = streamEvent.path("delta");
        if (!"content_block_delta".equals(type) || !"text_delta".equals(delta.path("type").asString(""))) {
            return "";
        }
        String text = delta.path("text").asString("");
        if (text.isEmpty()) {
            return "";
        }
        textSeen = true;
        if (separatorDue) {
            separatorDue = false;
            return "\n\n" + text;
        }
        return text;
    }

    private String failureMessage(JsonNode result) {
        int status = result.path("api_error_status").asInt(-1);
        if ("authentication_failed".equals(errorCode) || status == 401 || status == 403) {
            return "Claude ist nicht angemeldet – im Terminal „claude auth login“ ausführen";
        }
        if ("model_not_found".equals(errorCode) || status == 404) {
            return "Modell nicht gefunden – in den Einstellungen ein anderes Modell wählen";
        }
        if ("rate_limit".equals(errorCode) || status == 429) {
            return "Claude-Nutzungslimit erreicht – bitte später erneut versuchen";
        }
        return "Die Anfrage an Claude ist fehlgeschlagen: " + result.path("result").asString("unbekannter Fehler");
    }
}
