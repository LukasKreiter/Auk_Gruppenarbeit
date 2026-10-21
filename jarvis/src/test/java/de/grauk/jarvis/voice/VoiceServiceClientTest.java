package de.grauk.jarvis.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import de.grauk.jarvis.settings.SettingsService;
import de.grauk.jarvis.status.MetricsService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceServiceClientTest {

    @TempDir Path dir;

    private final SettingsService settings = mock(SettingsService.class);
    private final MetricsService metrics = mock(MetricsService.class);

    private VoiceSelector selector(String model) {
        return new VoiceSelector(model, model + ".json", 0, settings);
    }

    private VoiceServiceClient client(String executable) {
        return new VoiceServiceClient(selector(dir.resolve("default.onnx").toString()), settings, metrics, executable);
    }

    @Test
    void rejectsBlankAndTooLongText() {
        VoiceServiceClient client = client("piper");

        assertThatThrownBy(() -> client.synthesize(" ", "de")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.synthesize("x".repeat(VoiceServiceClient.MAX_TEXT_LENGTH + 1), "de"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(VoiceServiceClient.MAX_TEXT_LENGTH));
    }

    @Test
    void refusesWhenTtsIsDisabledInSettings() {
        when(settings.isTtsEnabled()).thenReturn(false);

        assertThatThrownBy(() -> client("piper").synthesize("Hallo", "de"))
                .isInstanceOf(VoiceException.class)
                .hasMessageContaining("deaktiviert");
    }

    @Test
    void missingExecutableIsReportedAndReleasesThePermit() {
        when(settings.isTtsEnabled()).thenReturn(true);
        VoiceServiceClient client = client(dir.resolve("gibt-es-nicht.exe").toString());

        // öfter als MAX_CONCURRENT: jeder Versuch muss den Startfehler melden, nicht "ausgelastet"
        for (int i = 0; i <= VoiceServiceClient.MAX_CONCURRENT; i++) {
            assertThatThrownBy(() -> client.synthesize("Hallo", "de"))
                    .isInstanceOf(VoiceException.class)
                    .hasMessageContaining("nicht gestartet")
                    .hasCauseInstanceOf(IOException.class);
        }
        verify(metrics, never()).recordTts(anyLong());
    }

    @Test
    void selectorUsesDefaultModelWithoutConfiguredVoice() {
        when(settings.getVoiceFor("de")).thenReturn(null);

        VoiceSelector.Voice voice = selector("C:/piper/model/de.onnx").select("de");

        assertThat(voice.model()).isEqualTo("C:/piper/model/de.onnx");
        assertThat(voice.config()).isEqualTo("C:/piper/model/de.onnx.json");
    }

    @Test
    void selectorResolvesConfiguredVoiceNextToDefaultModel() throws IOException {
        Files.createFile(dir.resolve("en_US-lessac-high.onnx"));
        when(settings.getVoiceFor("en")).thenReturn("en_US-lessac-high");

        VoiceSelector.Voice voice = selector(dir.resolve("default.onnx").toString()).select("en");

        assertThat(voice.model()).isEqualTo(dir.resolve("en_US-lessac-high.onnx").toAbsolutePath().toString());
        assertThat(voice.config()).endsWith("en_US-lessac-high.onnx.json");
    }

    @Test
    void selectorRejectsPathTraversalAndMissingVoices() {
        VoiceSelector selector = selector(dir.resolve("default.onnx").toString());

        when(settings.getVoiceFor("de")).thenReturn("../../secret");
        assertThatThrownBy(() -> selector.select("de")).isInstanceOf(VoiceException.class).hasMessageContaining("Ungültig");

        when(settings.getVoiceFor("de")).thenReturn("fehlt");
        assertThatThrownBy(() -> selector.select("de")).isInstanceOf(VoiceException.class).hasMessageContaining("nicht gefunden");
    }
}
