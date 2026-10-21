package de.grauk.jarvis.web;

import java.io.IOException;
import java.util.Locale;
import java.util.stream.Collectors;

import de.grauk.jarvis.assistant.ProviderUnavailableException;
import de.grauk.jarvis.conversation.ConversationNotFoundException;
import de.grauk.jarvis.knowledge.KnowledgeException;
import de.grauk.jarvis.tools.ConfirmationNotFoundException;
import de.grauk.jarvis.voice.VoiceException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Einheitliche Fehlerantworten als {@link ProblemDetail} ({@code application/problem+json}) mit
 * {@code status}, {@code title} und einer für Nutzer lesbaren {@code detail}-Meldung. Stacktraces und
 * Exception-Klassennamen verlassen den Server nie; unerwartete Fehler werden geloggt und als 500 gemeldet.
 * Der Inhaltstyp wird explizit gesetzt, damit Fehler auch bei {@code Accept: text/event-stream} lesbar sind.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({ConversationNotFoundException.class, ConfirmationNotFoundException.class})
    ResponseEntity<Object> notFound(RuntimeException e, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> badRequest(IllegalArgumentException e, WebRequest request) {
        // Die Services werfen bewusst Validierungstexte; die Meldung geht an den Client. Der Werfer steht im WARN-Log,
        // damit eine IAE aus einer Bibliothek oder einem Programmierfehler nicht unsichtbar bleibt; Stacktrace auf debug.
        StackTraceElement[] trace = e.getStackTrace();
        log.warn("Ungültige Anfrage bei {}: {} (geworfen in {})", request.getDescription(false), e.getMessage(),
                trace.length > 0 ? trace[0] : "?");
        log.debug("Stacktrace der ungültigen Anfrage", e);
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> typeMismatch(MethodArgumentTypeMismatchException e, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Ungültiger Wert für '" + e.getName() + "'");
    }

    @ExceptionHandler(StreamActiveException.class)
    ResponseEntity<Object> streamActive(StreamActiveException e, WebRequest request) {
        return problem(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ProviderUnavailableException.class)
    ResponseEntity<Object> providerUnavailable(ProviderUnavailableException e, WebRequest request) {
        log.warn("Provider nicht verfügbar bei {}: {}", request.getDescription(false), e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(KnowledgeException.class)
    ResponseEntity<Object> knowledge(KnowledgeException e, WebRequest request) {
        log.warn("Wissens-Fehler: {}", e.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(VoiceException.class)
    ResponseEntity<Object> voice(VoiceException e, WebRequest request) {
        // Ursache (z. B. Piper-Fehlerausgabe) nur ins Log, der Client bekommt die lesbare Meldung
        log.warn("Sprachausgabe fehlgeschlagen: {}", e.getMessage(), e.getCause());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /**
     * Abgebrochene Verbindungen (Broken pipe, Connection reset) sind kein Serverfehler und können auf einem
     * {@code text/event-stream} ohnehin keinen JSON-Body mehr aufnehmen: leere Antwort, kein ERROR-Log.
     * {@code AsyncRequestNotUsableException} (Unterklasse von {@link IOException}) behandelt bereits die Basisklasse
     * ohne Body.
     */
    @ExceptionHandler(IOException.class)
    @Nullable ResponseEntity<Object> ioException(IOException e, WebRequest request) {
        if (isClientAbort(e)) {
            log.debug("Client hat die Verbindung bei {} abgebrochen: {}", request.getDescription(false), e.toString());
            return null;
        }
        return unexpected(e, request);
    }

    static boolean isClientAbort(@Nullable Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getClass().getSimpleName().equals("ClientAbortException")) {
                return true;
            }
            String message = t.getMessage();
            if (message != null) {
                String m = message.toLowerCase(Locale.ROOT);
                if (m.contains("broken pipe") || m.contains("connection reset") || m.contains("connection abort")) {
                    return true;
                }
            }
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
        log.error("Unerwarteter Fehler bei {}", request.getDescription(false), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Interner Fehler");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map((FieldError f) -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, detail.isEmpty() ? "Ungültige Eingabe" : detail);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response == null || !(response.getBody() instanceof ProblemDetail)) {
            return response;
        }
        HttpHeaders withType = new HttpHeaders();
        withType.putAll(response.getHeaders());
        withType.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(response.getBody(), withType, response.getStatusCode());
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
