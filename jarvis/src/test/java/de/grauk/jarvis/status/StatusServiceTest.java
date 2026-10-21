package de.grauk.jarvis.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ChatProviderRegistry;
import de.grauk.jarvis.status.MetricsService.Latencies;
import de.grauk.jarvis.status.MetricsService.SystemSnapshot;
import de.grauk.jarvis.status.StatusService.Light;
import de.grauk.jarvis.status.StatusService.State;
import de.grauk.jarvis.status.StatusService.StatusReport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

class StatusServiceTest {

    private final ChatProviderRegistry registry = mock(ChatProviderRegistry.class);
    private final OllamaHealthIndicator ollama = mock(OllamaHealthIndicator.class);
    private final ClaudeHealthIndicator claude = mock(ClaudeHealthIndicator.class);
    private final VoiceHealthIndicator voiceIndicator = mock(VoiceHealthIndicator.class);
    private final VoiceHealthClient voice = mock(VoiceHealthClient.class);
    private final MetricsService metrics = mock(MetricsService.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final StatusService service =
            new StatusService(ollama, claude, voiceIndicator, voice, registry, metrics, dataSource);

    @BeforeEach
    void setUp() throws Exception {
        when(registry.active()).thenReturn(ChatProvider.OLLAMA);
        when(registry.models(ChatProvider.OLLAMA)).thenReturn(List.of("qwen3:8b"));
        when(ollama.health()).thenReturn(Health.up().build());
        when(claude.health()).thenReturn(Health.unknown().withDetail("message", "kein Key").build());
        when(voiceIndicator.health()).thenReturn(Health.up().build());
        when(voice.current()).thenReturn(VoiceHealth.down("weg", Instant.EPOCH));
        when(metrics.snapshot()).thenReturn(new SystemSnapshot(0.5, 1, 2, null, null));
        when(metrics.lastLatencies()).thenReturn(new Latencies(null, null, null));
        when(metrics.source()).thenReturn("oshi");
        java.sql.Connection c = mock(java.sql.Connection.class);
        when(c.isValid(1)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(c);
    }

    private static Light light(StatusReport r, String key) {
        return r.lights().stream().filter(l -> l.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void throwingPartsYieldPartialReport() throws Exception {
        when(ollama.health()).thenThrow(new IllegalStateException("boom"));
        when(voice.current()).thenThrow(new IllegalStateException("boom"));
        when(metrics.snapshot()).thenThrow(new IllegalStateException("boom"));
        when(dataSource.getConnection()).thenThrow(new SQLException("db weg"));

        StatusReport r = service.report();

        assertThat(r.lights()).hasSize(4);
        assertThat(light(r, "ollama").state()).isEqualTo(State.DOWN);
        assertThat(light(r, "ollama").message()).contains("IllegalStateException");
        assertThat(light(r, "db").state()).isEqualTo(State.DOWN);
        assertThat(light(r, "db").message()).contains("SQLException");
        assertThat(light(r, "voice").state()).isEqualTo(State.UP);
        assertThat(r.ollamaModels()).isEmpty();
        assertThat(r.voiceModels()).isEmpty();
        assertThat(r.gpu()).isNull();
        assertThat(r.cpuLoad()).isNegative();
    }

    @Test
    void activeAnthropicWithoutKeyIsDown() {
        when(registry.active()).thenReturn(ChatProvider.ANTHROPIC);

        Light claudeLight = light(service.report(), "claude");

        assertThat(claudeLight.state()).isEqualTo(State.DOWN);
        assertThat(claudeLight.message()).isEqualTo("aktiver Provider nicht verfügbar (kein API-Key)");
    }

    @Test
    void inactiveAnthropicWithoutKeyStaysUnknown() {
        assertThat(light(service.report(), "claude").state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void activeOllamaDownStaysDownAndReportsSource() {
        when(ollama.health()).thenReturn(Health.down().withDetail("message", "LLM-Server nicht erreichbar").build());

        StatusReport r = service.report();

        assertThat(light(r, "ollama").state()).isEqualTo(State.DOWN);
        assertThat(r.metricsSource()).isEqualTo("oshi");
    }
}
