package de.grauk.jarvis.assistant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Startet die Claude-Code-CLI. Einziger Ort, der den Prozess erzeugt; Tests ersetzen {@link #start}. */
@Component
class ClaudeCliLauncher {

    /** Die CLI bevorzugt diese Variablen vor der Anmeldung; Jarvis soll immer das angemeldete Abo nutzen (ADR 0006). */
    private static final List<String> CREDENTIAL_VARIABLES = List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN");

    private final String executable;

    ClaudeCliLauncher(ClaudeCliProperties properties) {
        this.executable = properties.executable();
    }

    ProcessBuilder command(List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        removeCredentials(builder.environment());
        return builder;
    }

    static void removeCredentials(Map<String, String> environment) {
        CREDENTIAL_VARIABLES.forEach(environment::remove);
    }

    Process start(ProcessBuilder builder) throws IOException {
        return builder.start();
    }
}
