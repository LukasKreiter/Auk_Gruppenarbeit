package de.grauk.jarvis.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import ch.qos.logback.classic.Level;
import de.grauk.jarvis.status.LogBufferAppender;
import de.grauk.jarvis.status.LogBufferAppender.LogLine;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Log-Ansicht der Status-Seite: HTML-Fragment (HTMX-Polling) und JSON aus dem {@link LogBufferAppender}.
 *
 * <p>Parameter {@code level} (optional, {@code TRACE|DEBUG|INFO|WARN|ERROR}, Groß-/Kleinschreibung egal) filtert
 * auf "mindestens dieses Level"; leer = alle. Ungültig: API liefert 400 (über {@code ApiExceptionHandler}), das
 * Fragment HTTP 200 mit Model-Attribut {@code error} (wie die übrigen Fragmente).
 *
 * <p>Logzeilen können beliebige Fremdinhalte enthalten (Nutzereingaben, Exceptions). Das Escaping übernimmt
 * Thymeleaf: Templates müssen {@code th:text} verwenden, nie {@code th:utext}.
 */
@Controller
public class LogController {

    static final String LOG_LIST = "fragments/logs :: list";
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** Eintrag für Fragment und JSON: {@code time} (HH:mm:ss.SSS), {@code level}, {@code text} (volle Zeile). */
    public record LogEntry(String time, String level, String text) {
        static LogEntry from(LogLine line) {
            return new LogEntry(TIME.format(Instant.ofEpochMilli(line.timestamp())), line.level().toString(),
                    line.text());
        }
    }

    /** Attribute: lines (Liste von {@link LogEntry}, älteste zuerst), level (String, "" = alle); ggf. error. */
    @GetMapping("/fragments/logs")
    public String logFragment(@RequestParam(required = false) String level, Model model) {
        model.addAttribute("level", level == null ? "" : level.strip().toUpperCase(Locale.ROOT));
        try {
            model.addAttribute("lines", entries(level));
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("lines", List.of());
        }
        return LOG_LIST;
    }

    /** JSON-Liste von {@link LogEntry}; ungültiges Level = 400. */
    @GetMapping("/api/logs")
    @ResponseBody
    public List<LogEntry> logs(@RequestParam(required = false) String level) {
        return entries(level);
    }

    private static List<LogEntry> entries(String level) {
        return LogBufferAppender.getLines(parse(level)).stream().map(LogEntry::from).toList();
    }

    private static Level parse(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        return switch (level.strip().toUpperCase(Locale.ROOT)) {
            case "TRACE" -> Level.TRACE;
            case "DEBUG" -> Level.DEBUG;
            case "INFO" -> Level.INFO;
            case "WARN" -> Level.WARN;
            case "ERROR" -> Level.ERROR;
            default -> throw new IllegalArgumentException("Ungültiges Log-Level: " + level.strip()
                    + " (erlaubt: TRACE, DEBUG, INFO, WARN, ERROR)");
        };
    }
}
