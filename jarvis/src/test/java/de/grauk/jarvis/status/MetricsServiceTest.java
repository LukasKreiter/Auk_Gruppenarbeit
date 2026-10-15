package de.grauk.jarvis.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ChatProviderRegistry;
import de.grauk.jarvis.status.MetricsService.SystemSnapshot;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class MetricsServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final VoiceHealthClient voice = mock(VoiceHealthClient.class);
    private final MetricsService metrics = new MetricsService(registry, voice);

    @Test
    void recordsLatenciesAsTimersAndKeepsLastValues() {
        assertThat(metrics.lastLatencies()).isEqualTo(new MetricsService.Latencies(null, null, null));

        metrics.recordStt(120);
        metrics.recordLlm(800);
        metrics.recordLlm(900);
        metrics.recordTts(50);

        assertThat(metrics.lastLatencies()).isEqualTo(new MetricsService.Latencies(120L, 900L, 50L));
        var llm = registry.get("jarvis.latency").tag("stage", "llm").timer();
        assertThat(llm.count()).isEqualTo(2);
        assertThat(llm.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1700);
        assertThat(registry.get("jarvis.latency").tag("stage", "stt").timer().count()).isEqualTo(1);
        assertThat(registry.get("jarvis.latency").tag("stage", "tts").timer().count()).isEqualTo(1);
    }

    @Test
    void restoredLatenciesDoNotOverrideMeasuredOnesNorTouchTimers() {
        metrics.recordLlm(10);
        metrics.restoreLatencies(5L, 99L, null);

        assertThat(metrics.lastLatencies()).isEqualTo(new MetricsService.Latencies(5L, 10L, null));
        assertThat(registry.get("jarvis.latency").tag("stage", "stt").timer().count()).isZero();
    }

    @Test
    void snapshotWithoutGpuHasNullGpuValuesAndPlausibleMemory() {
        when(voice.current()).thenReturn(VoiceHealth.down("weg", Instant.EPOCH));

        SystemSnapshot s = metrics.snapshot();

        assertThat(s.gpuMemoryUsedBytes()).isNull();
        assertThat(s.gpuMemoryTotalBytes()).isNull();
        assertThat(s.totalMemoryBytes()).isPositive();
        assertThat(s.usedMemoryBytes()).isBetween(0L, s.totalMemoryBytes());
        assertThat(s.cpuLoad()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void invalidCpuLoadIsUnknown() {
        assertThat(MetricsService.sanitize(Double.NaN)).isEqualTo(-1);
        assertThat(MetricsService.sanitize(-0.5)).isEqualTo(-1);
        assertThat(MetricsService.sanitize(0.4)).isEqualTo(0.4);
    }

    @Test
    void snapshotTakesGpuMemoryFromVoiceHealth() {
        when(voice.current()).thenReturn(new VoiceHealth(true, "ok", List.of(), "GPU", 100L, 200L, 0.1, Instant.EPOCH));

        SystemSnapshot s = metrics.snapshot();

        assertThat(s.gpuMemoryUsedBytes()).isEqualTo(100L);
        assertThat(s.gpuMemoryTotalBytes()).isEqualTo(200L);
    }

    @Test
    void claudeIndicatorIsUnknownWithoutKeyAndUpWithKey() {
        ChatProviderRegistry providers = mock(ChatProviderRegistry.class);
        when(providers.available()).thenReturn(Map.of(ChatProvider.OLLAMA, false, ChatProvider.ANTHROPIC, false));
        var claude = new ClaudeHealthIndicator(providers);
        assertThat(claude.health().getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(claude.health().getDetails()).containsEntry("message", "kein Key");

        when(providers.available()).thenReturn(Map.of(ChatProvider.OLLAMA, false, ChatProvider.ANTHROPIC, true));
        assertThat(claude.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void ollamaIndicatorUsesRegistry() {
        ChatProviderRegistry providers = mock(ChatProviderRegistry.class);
        when(providers.models(ChatProvider.OLLAMA)).thenReturn(List.of("qwen3:8b"));
        when(providers.available()).thenReturn(Map.of(ChatProvider.OLLAMA, true, ChatProvider.ANTHROPIC, false));
        var ollama = new OllamaHealthIndicator(providers);

        assertThat(ollama.health().getStatus()).isEqualTo(Status.UP);
        assertThat(ollama.health().getDetails()).containsEntry("models", List.of("qwen3:8b"));

        when(providers.available()).thenReturn(Map.of(ChatProvider.OLLAMA, false, ChatProvider.ANTHROPIC, false));
        assertThat(ollama.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
