package de.grauk.jarvis.web;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Konfiguration unter {@code jarvis.web}.
 *
 * @param allowedHosts   erlaubte Hostnamen (ohne Schema und Port) für den Host-Header (DNS-Rebinding-Schutz)
 *                       und für Origin/Referer zustandsändernder Requests
 * @param sseTimeout     maximale Dauer eines Chat-Streams ({@code POST .../messages}); danach wird die Anfrage abgebrochen
 * @param allowedOrigins zusätzlich erlaubte Origins (Schema://Host[:Port], z. B. {@code http://localhost:5173}) für ein
 *                       Frontend-Dev-Setup; Default leer. Sonst ist bei Origin/Referer nur der eigene Port erlaubt.
 */
@ConfigurationProperties("jarvis.web")
public record JarvisWebProperties(
        @DefaultValue({"localhost", "127.0.0.1"}) List<String> allowedHosts,
        @DefaultValue("10m") Duration sseTimeout,
        @DefaultValue List<String> allowedOrigins) {
}
