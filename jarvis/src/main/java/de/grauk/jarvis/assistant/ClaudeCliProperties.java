package de.grauk.jarvis.assistant;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration unter {@code jarvis.assistant.claude-cli.*}.
 *
 * @param executable  Programm auf dem PATH oder absoluter Pfad zur Claude-Code-CLI
 * @param idleTimeout längste Zeit ohne Antworttext, bevor ein Lauf abgebrochen wird; muss über
 *                    {@code jarvis.tools.confirmation-timeout} liegen, weil der Lauf während einer Bestätigung wartet
 */
@ConfigurationProperties("jarvis.assistant.claude-cli")
public record ClaudeCliProperties(String executable, Duration idleTimeout) {

    public ClaudeCliProperties {
        executable = executable == null || executable.isBlank() ? "claude" : executable;
        idleTimeout = idleTimeout == null ? Duration.ofMinutes(5) : idleTimeout;
    }
}
