package de.grauk.jarvis.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Fehler-Ereignisse sind echte Ausgaben der CLI 2.1 (Modell unbekannt, nicht angemeldet). */
class ClaudeStreamParserTest {

    private final ClaudeStreamParser parser = new ClaudeStreamParser();

    private static String delta(String text) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}}";
    }

    private static final String MESSAGE_START = "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\"}}";

    @Test
    void returnsTextDeltas() {
        assertThat(parser.accept(delta("Hal"))).isEqualTo("Hal");
        assertThat(parser.accept(delta("lo äöü"))).isEqualTo("lo äöü");
    }

    @Test
    void ignoresThinkingStatusAndNonJsonLines() {
        assertThat(parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}}")).isEmpty();
        assertThat(parser.accept("{\"type\":\"system\",\"subtype\":\"init\"}")).isEmpty();
        assertThat(parser.accept("keine JSON-Zeile")).isEmpty();
        assertThat(parser.finished()).isFalse();
    }

    @Test
    void separatesTextOfConsecutiveModelRounds() {
        assertThat(parser.accept(MESSAGE_START)).isEmpty();
        assertThat(parser.accept(delta("Ich schaue nach."))).isEqualTo("Ich schaue nach.");
        assertThat(parser.accept(MESSAGE_START)).isEmpty();
        assertThat(parser.accept(delta("Erledigt"))).isEqualTo("\n\nErledigt");
        assertThat(parser.accept(delta("!"))).isEqualTo("!");
    }

    @Test
    void successfulResultFinishesWithoutFailure() {
        parser.accept("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\"}");

        assertThat(parser.finished()).isTrue();
        assertThat(parser.failure()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "authentication_failed, null, nicht angemeldet",
            "model_not_found,       404,  Modell nicht gefunden",
            "rate_limit,            null, Nutzungslimit",
            "unknown,               429,  Nutzungslimit",
            "unknown,               401,  nicht angemeldet"})
    void mapsCliErrorsToUserMessages(String error, String status, String expected) {
        String answer = parser.accept("{\"type\":\"assistant\",\"error\":\"" + error + "\",\"message\":{\"model\":"
                + "\"<synthetic>\",\"content\":[{\"type\":\"text\",\"text\":\"Not logged in\"}]}}");
        parser.accept("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":" + status
                + ",\"result\":\"Not logged in\"}");

        assertThat(answer).as("Fehlertext ist keine Antwort").isEmpty();
        assertThat(parser.failure()).hasValueSatisfying(text -> assertThat(text).contains(expected));
    }

    @Test
    void unknownFailureKeepsTheCliText() {
        parser.accept("{\"type\":\"result\",\"is_error\":true,\"api_error_status\":500,\"result\":\"API Error\"}");

        assertThat(parser.failure()).hasValue("Die Anfrage an Claude ist fehlgeschlagen: API Error");
    }
}
