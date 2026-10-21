package de.grauk.jarvis.tools.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.grauk.jarvis.tools.RequiresConfirmation;
import de.grauk.jarvis.tools.ToolsProperties;

class ApplicationToolsTest {

    private final List<List<String>> started = new ArrayList<>();
    private final ApplicationTools tools = new ApplicationTools(
            new ToolsProperties(Duration.ofSeconds(1), null, new ToolsProperties.OpenApplication(
                    Map.of("notepad", "C:\\Windows\\System32\\notepad.exe", "rechner", "C:\\Windows\\System32\\calc.exe"))),
            started::add);

    @Test
    void startsAllowlistedProgramCaseInsensitiveWithoutShellOrArguments() {
        String result = tools.openApplication(" Notepad ");

        assertThat(result).contains("gestartet");
        assertThat(started).containsExactly(List.of("C:\\Windows\\System32\\notepad.exe"));
    }

    @Test
    void rejectsUnknownProgramAndListsAllowedNames() {
        String result = tools.openApplication("powershell");

        assertThat(result).contains("nicht erlaubt").contains("notepad").contains("rechner");
        assertThat(started).isEmpty();
    }

    @Test
    void rejectsShellInjectionAttempts() {
        tools.openApplication("notepad & calc");
        tools.openApplication("cmd /c notepad");
        tools.openApplication("C:\\Windows\\System32\\cmd.exe");
        tools.openApplication(null);

        assertThat(started).isEmpty();
    }

    @Test
    void reportsStartFailure() {
        ApplicationTools failing = new ApplicationTools(
                new ToolsProperties(Duration.ofSeconds(1), null, new ToolsProperties.OpenApplication(
                        Map.of("notepad", "x.exe"))),
                command -> {
                    throw new IOException("kaputt");
                });

        assertThat(failing.openApplication("notepad")).contains("konnte nicht gestartet werden");
    }

    @Test
    void isMarkedAsRequiringConfirmation() throws Exception {
        assertThat(ApplicationTools.class.getMethod("openApplication", String.class)
                .isAnnotationPresent(RequiresConfirmation.class)).isTrue();
    }
}
