package de.grauk.jarvis.web;

import de.grauk.jarvis.web.Dtos.SettingsRequest;
import de.grauk.jarvis.web.Dtos.SettingsView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Einstellungen des Assistenten samt Provider-Verfügbarkeit und Modellliste pro Provider. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsWebService settings;

    SettingsController(SettingsWebService settings) {
        this.settings = settings;
    }

    @GetMapping
    public SettingsView get() {
        return settings.view();
    }

    /** @throws IllegalArgumentException ungültige Werte, z. B. Modell unbekannt oder Temperatur außerhalb des Bereichs (400) */
    @PutMapping
    public SettingsView update(@RequestBody SettingsRequest request) {
        return settings.update(request);
    }

    /** Setzt alle Einstellungen auf die Defaults aus der Konfiguration zurück. */
    @PostMapping("/reset")
    public SettingsView reset() {
        return settings.reset();
    }
}
