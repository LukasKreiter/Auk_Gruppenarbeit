package de.grauk.jarvis.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Prüft die Tool-Erkennung im echten Kontext: alle Built-ins gefunden, Defaults aus tool_setting korrekt. */
@SpringBootTest
class ToolRegistryIntegrationTest {

    @Autowired
    ToolRegistry registry;

    @Test
    void discoversAllBuiltinToolsWithDefaults() {
        Map<String, ToolRegistry.ToolInfo> tools = registry.all().stream()
                .collect(Collectors.toMap(ToolRegistry.ToolInfo::name, t -> t));

        assertThat(tools.keySet()).containsExactlyInAnyOrder("getCurrentDateTime", "setTimer", "getWeather",
                "rememberFact", "searchKnowledge", "openApplication", "getSystemStatus");
        assertThat(tools.values()).allMatch(ToolRegistry.ToolInfo::enabled);
        assertThat(tools.get("openApplication").requiresConfirmation()).isTrue();
        assertThat(tools.values().stream().filter(ToolRegistry.ToolInfo::requiresConfirmation))
                .extracting(ToolRegistry.ToolInfo::name).containsExactly("openApplication");
        assertThat(registry.enabledCallbacks()).hasSize(7);
    }
}
