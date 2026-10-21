package de.grauk.jarvis.voice;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.grauk.jarvis.conversation.ConversationService;
import de.grauk.jarvis.web.JarvisWebProperties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VoiceController.class)
@EnableConfigurationProperties(JarvisWebProperties.class)
class VoiceControllerTest {

    private static final String ORIGIN = "http://127.0.0.1"; // MockMvc-Port 80; der Filter erlaubt nur den eigenen Port

    @Autowired MockMvc mvc;
    @MockitoBean VoiceServiceClient voice;
    @MockitoBean ConversationService conversations;

    @Test
    void synthesizeReturnsWavAndRecordsTtsDurationOnMessage() throws Exception {
        when(voice.synthesize("Hallo", "de")).thenReturn(new byte[] {1, 2, 3});

        mvc.perform(post("/api/voice/synthesize").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hallo\",\"language\":\"de\",\"messageId\":7}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/wav"))
                .andExpect(content().bytes(new byte[] {1, 2, 3}));
        verify(conversations).recordTtsMillis(eq(7L), anyLong());
    }

    @Test
    void getIsNotAllowedSoCrossSiteImagesCannotTriggerPiper() throws Exception {
        mvc.perform(get("/api/voice/synthesize").param("text", "Hallo"))
                .andExpect(status().isMethodNotAllowed());
        verify(voice, never()).synthesize(anyString(), anyString());
    }

    @Test
    void foreignOriginIsRejected() throws Exception {
        mvc.perform(post("/api/voice/synthesize").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Hallo\"}"))
                .andExpect(status().isForbidden());
        verify(voice, never()).synthesize(anyString(), anyString());
    }

    @Test
    void blankOrTooLongTextIsBadRequest() throws Exception {
        mvc.perform(post("/api/voice/synthesize").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/voice/synthesize").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "x".repeat(VoiceServiceClient.MAX_TEXT_LENGTH + 1) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void voiceFailureIsServiceUnavailableWithReadableMessage() throws Exception {
        when(voice.synthesize("Hallo", null)).thenThrow(new VoiceException("Die Sprachausgabe ist fehlgeschlagen.",
                new IllegalStateException("Piper: model not found C:/geheim")));

        mvc.perform(post("/api/voice/synthesize").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hallo\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Die Sprachausgabe ist fehlgeschlagen."));
    }
}
