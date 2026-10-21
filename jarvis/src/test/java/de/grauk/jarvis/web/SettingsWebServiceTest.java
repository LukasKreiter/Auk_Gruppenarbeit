package de.grauk.jarvis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ChatProviderRegistry;
import de.grauk.jarvis.settings.AssistantSettings;
import de.grauk.jarvis.settings.SettingsService;
import de.grauk.jarvis.web.Dtos.SettingsRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SettingsWebServiceTest {

    private final SettingsService settings = mock(SettingsService.class);
    private final ChatProviderRegistry registry = mock(ChatProviderRegistry.class);
    private SettingsWebService web;

    @BeforeEach
    void setUp() {
        when(settings.update(any(AssistantSettings.class))).thenAnswer(inv -> inv.getArgument(0));
        web = new SettingsWebService(settings, registry);
    }

    private void stored(ChatProvider provider, String model, double temperature) {
        when(settings.getSettings()).thenReturn(
                new AssistantSettings(provider, model, "Prompt", temperature, false, null, null, true));
    }

    private static SettingsRequest request(ChatProvider provider, String model, Double temperature) {
        return new SettingsRequest(provider, model, null, temperature, null, null, null, null);
    }

    private AssistantSettings saved() {
        ArgumentCaptor<AssistantSettings> captor = ArgumentCaptor.forClass(AssistantSettings.class);
        verify(settings).update(captor.capture());
        return captor.getValue();
    }

    @Test
    void providerSwitchWithoutModelTakesFirstModelOfNewProvider() {
        stored(ChatProvider.OLLAMA, "qwen3:8b", 0.7);
        when(registry.models(ChatProvider.ANTHROPIC)).thenReturn(List.of("claude-a", "claude-b"));

        web.update(request(ChatProvider.ANTHROPIC, null, null));

        assertThat(saved().getModel()).isEqualTo("claude-a");
    }

    @Test
    void providerSwitchWithEmptyListUsesConfiguredFallback() {
        stored(ChatProvider.ANTHROPIC, "claude-a", 0.7);
        when(registry.models(ChatProvider.OLLAMA)).thenReturn(List.of());
        when(settings.fallbackModel(ChatProvider.OLLAMA)).thenReturn("qwen3:8b");

        web.update(request(ChatProvider.OLLAMA, null, null));

        assertThat(saved().getModel()).isEqualTo("qwen3:8b");
    }

    @Test
    void providerSwitchWithEmptyListAndNoFallbackFails() {
        stored(ChatProvider.OLLAMA, "qwen3:8b", 0.7);
        when(registry.models(ChatProvider.ANTHROPIC)).thenReturn(List.of());
        when(settings.fallbackModel(ChatProvider.ANTHROPIC)).thenReturn(null);

        assertThatThrownBy(() -> web.update(request(ChatProvider.ANTHROPIC, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void explicitModelIsKeptOnProviderSwitch() {
        stored(ChatProvider.OLLAMA, "qwen3:8b", 0.7);

        web.update(request(ChatProvider.ANTHROPIC, "claude-x", null));

        assertThat(saved().getModel()).isEqualTo("claude-x");
    }

    @Test
    void switchToAnthropicClampsTemperatureAboveOne() {
        stored(ChatProvider.OLLAMA, "qwen3:8b", 1.6);
        when(registry.models(ChatProvider.ANTHROPIC)).thenReturn(List.of("claude-a"));

        web.update(request(ChatProvider.ANTHROPIC, null, null));

        assertThat(saved().getTemperature()).isEqualTo(1.0);
    }

    @Test
    void explicitTooHighTemperatureIsPassedOnForRejection() {
        stored(ChatProvider.OLLAMA, "qwen3:8b", 0.7);
        when(registry.models(ChatProvider.ANTHROPIC)).thenReturn(List.of("claude-a"));
        when(settings.update(any(AssistantSettings.class)))
                .thenThrow(new IllegalArgumentException("Temperatur muss zwischen 0.0 und 1.0 liegen"));

        assertThatThrownBy(() -> web.update(request(ChatProvider.ANTHROPIC, null, 1.5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temperatureStaysWhenProviderUnchanged() {
        stored(ChatProvider.OLLAMA, "qwen3:8b", 1.6);

        web.update(request(null, null, null));

        assertThat(saved().getTemperature()).isEqualTo(1.6);
        assertThat(saved().getModel()).isEqualTo("qwen3:8b");
    }
}
