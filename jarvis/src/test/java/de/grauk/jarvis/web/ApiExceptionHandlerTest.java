package de.grauk.jarvis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.WebRequest;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final WebRequest request = mock(WebRequest.class);

    @Test
    void clientAbortIsNoOpWithoutBody() {
        assertThat(handler.ioException(new IOException("Broken pipe"), request)).isNull();
        assertThat(handler.ioException(new IOException("Eine vorhandene Verbindung wurde abgebrochen",
                new IOException("Connection reset by peer")), request)).isNull();
    }

    @Test
    void otherIoExceptionIs500() {
        var response = handler.ioException(new IOException("Platte voll"), request);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void activeStreamIs409() {
        var response = handler.streamActive(new StreamActiveException("Antwort läuft noch"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
