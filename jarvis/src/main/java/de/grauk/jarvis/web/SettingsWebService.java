package de.grauk.jarvis.web;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ChatProviderRegistry;
import de.grauk.jarvis.settings.AssistantSettings;
import de.grauk.jarvis.settings.SettingsService;
import de.grauk.jarvis.web.Dtos.SettingsDto;
import de.grauk.jarvis.web.Dtos.SettingsRequest;
import de.grauk.jarvis.web.Dtos.SettingsView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Baut aus {@link SettingsService} und {@link ChatProviderRegistry} die Sicht für Einstellungsseite und API. */
@Component
class SettingsWebService {

    private static final Logger log = LoggerFactory.getLogger(SettingsWebService.class);

    private final SettingsService settings;
    private final ChatProviderRegistry registry;

    SettingsWebService(SettingsService settings, ChatProviderRegistry registry) {
        this.settings = settings;
        this.registry = registry;
    }

    SettingsView view() {
        return view(settings.getSettings());
    }

    SettingsView view(AssistantSettings current) {
        Map<ChatProvider, List<String>> models = new EnumMap<>(ChatProvider.class);
        for (ChatProvider provider : ChatProvider.values()) {
            models.put(provider, registry.models(provider));
        }
        return new SettingsView(SettingsDto.from(current), registry.available(), models,
                SettingsService.DEFAULT_SYSTEM_PROMPT);
    }

    /** Teil-Update: {@code null} = unverändert; Stimmen: leer = zurücksetzen. @throws IllegalArgumentException bei ungültigen Werten */
    SettingsView update(SettingsRequest request) {
        AssistantSettings existing = settings.getSettings();
        ChatProvider provider = request.provider() != null ? request.provider() : existing.getProvider();
        boolean providerChanged = provider != existing.getProvider();
        String model = request.model();
        if (model == null && providerChanged) {
            model = modelForNewProvider(provider);
        }
        double temperature;
        if (request.temperature() != null) {
            temperature = request.temperature();
        } else if (providerChanged && provider == ChatProvider.ANTHROPIC && existing.getTemperature() > 1.0) {
            log.info("Temperatur {} auf 1.0 begrenzt (Claude erlaubt höchstens 1.0)", existing.getTemperature());
            temperature = 1.0;
        } else {
            temperature = existing.getTemperature();
        }
        AssistantSettings changes = new AssistantSettings(provider,
                model != null ? model : existing.getModel(),
                request.systemPrompt() != null ? request.systemPrompt() : existing.getSystemPrompt(),
                temperature,
                request.thinkMode() != null ? request.thinkMode() : existing.isThinkMode(),
                voice(request.voiceDe(), existing.getVoiceDe()),
                voice(request.voiceEn(), existing.getVoiceEn()),
                request.ttsEnabled() != null ? request.ttsEnabled() : existing.isTtsEnabled());
        return view(settings.update(changes));
    }

    SettingsView reset() {
        return view(settings.resetToDefaults());
    }

    /** Erstes Modell des neuen Providers, sonst das konfigurierte; nie das Modell des alten Providers. */
    private String modelForNewProvider(ChatProvider provider) {
        List<String> available = registry.models(provider);
        if (!available.isEmpty()) {
            return available.getFirst();
        }
        String fallback = settings.fallbackModel(provider);
        if (fallback == null || fallback.isBlank()) {
            throw new IllegalArgumentException("Kein Modell für " + provider + " verfügbar – bitte ein Modell angeben");
        }
        log.warn("Modellliste von {} nicht abrufbar – verwende konfiguriertes Modell {}", provider, fallback);
        return fallback;
    }

    private static String voice(String requested, String existing) {
        if (requested == null) {
            return existing;
        }
        return requested.isBlank() ? null : requested.strip();
    }
}
