package de.grauk.jarvis.tools.builtin;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import de.grauk.jarvis.tools.RequiresConfirmation;
import de.grauk.jarvis.tools.ToolsProperties;

/** Startet Programme aus der Allowlist. Nie über eine Shell, nie mit Argumenten aus dem Modell. */
@Component
public class ApplicationTools {

    private static final Logger log = LoggerFactory.getLogger(ApplicationTools.class);

    /** Startet einen Prozess aus dem fertigen Kommando; austauschbar für Tests. */
    @FunctionalInterface
    interface ProcessStarter {
        void start(List<String> command) throws IOException;
    }

    private final Map<String, String> allowlist = new TreeMap<>();
    private final ProcessStarter starter;

    @Autowired
    public ApplicationTools(ToolsProperties properties) {
        this(properties, command -> new ProcessBuilder(command).start());
    }

    ApplicationTools(ToolsProperties properties, ProcessStarter starter) {
        properties.openApplication().allowlist()
                .forEach((name, path) -> allowlist.put(name.toLowerCase(Locale.ROOT), path));
        this.starter = starter;
    }

    @Tool(description = "Öffnet ein Programm auf dem PC des Nutzers, z. B. Notepad oder den Rechner. "
            + "Nur Programme aus einer festen Liste sind erlaubt; der Nutzer muss das Öffnen bestätigen.")
    @RequiresConfirmation
    public String openApplication(@ToolParam(description = "Name des Programms, z. B. 'notepad' oder 'rechner'") String name) {
        String key = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        String path = allowlist.get(key);
        if (path == null) {
            return "Das Programm '" + (name == null ? "" : name.strip()) + "' ist nicht erlaubt. Erlaubte Programme: "
                    + String.join(", ", allowlist.keySet()) + ".";
        }
        try {
            starter.start(List.of(path));
            return "Programm '" + key + "' wurde gestartet.";
        } catch (IOException e) {
            log.warn("Programm {} ({}) konnte nicht gestartet werden", key, path, e);
            return "Das Programm '" + key + "' konnte nicht gestartet werden: " + e.getMessage();
        }
    }
}
