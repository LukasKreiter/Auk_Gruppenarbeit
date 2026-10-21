package de.grauk.jarvis.tools;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Konfiguration unter {@code jarvis.tools}. */
@ConfigurationProperties("jarvis.tools")
public record ToolsProperties(
        @DefaultValue("60s") Duration confirmationTimeout,
        @DefaultValue Weather weather,
        @DefaultValue OpenApplication openApplication) {

    public record Weather(
            @DefaultValue("https://geocoding-api.open-meteo.com/v1/search") String geocodingUrl,
            @DefaultValue("https://api.open-meteo.com/v1/forecast") String forecastUrl,
            @DefaultValue("5s") Duration timeout) {
    }

    /** allowlist: Anzeigename (klein geschrieben) → Pfad der ausführbaren Datei. */
    public record OpenApplication(@DefaultValue Map<String, String> allowlist) {
    }
}
