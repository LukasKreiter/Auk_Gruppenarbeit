package de.grauk.jarvis.tools.builtin;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import de.grauk.jarvis.tools.ToolsProperties;

/** Wetter über Open-Meteo – das einzige Tool mit Internetzugriff. */
@Component
public class WeatherTools {

    private static final Logger log = LoggerFactory.getLogger(WeatherTools.class);

    record GeocodingResponse(List<Place> results) {}

    record Place(String name, String country, double latitude, double longitude) {}

    record ForecastResponse(Current current) {}

    record Current(double temperature_2m, double wind_speed_10m, int weather_code) {}

    private final ToolsProperties.Weather config;
    private final RestClient restClient;

    @Autowired
    public WeatherTools(ToolsProperties properties) {
        this(properties, createRestClient(properties.weather()));
    }

    WeatherTools(ToolsProperties properties, RestClient restClient) {
        this.config = properties.weather();
        this.restClient = restClient;
    }

    private static RestClient createRestClient(ToolsProperties.Weather config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.timeout());
        factory.setReadTimeout(config.timeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Tool(description = "Liefert das aktuelle Wetter (Temperatur, Wind, Wetterlage) für eine Stadt. "
            + "Verwenden bei Fragen zum Wetter oder zur Temperatur an einem Ort. Benötigt Internet.")
    public String getWeather(@ToolParam(description = "Name der Stadt, z. B. 'Wien' oder 'Bad Homburg'") String city) {
        if (city == null || city.isBlank()) {
            return "Es wurde keine Stadt angegeben.";
        }
        try {
            GeocodingResponse geo = restClient.get()
                    .uri(config.geocodingUrl(), b -> b.queryParam("name", "{name}")
                            .queryParam("count", 1).queryParam("language", "de").queryParam("format", "json")
                            .build(Map.of("name", city.strip())))
                    .retrieve().body(GeocodingResponse.class);
            if (geo == null || geo.results() == null || geo.results().isEmpty()) {
                return "Die Stadt '" + city.strip() + "' wurde nicht gefunden.";
            }
            Place place = geo.results().get(0);
            ForecastResponse forecast = restClient.get()
                    .uri(config.forecastUrl(), b -> b.queryParam("latitude", place.latitude())
                            .queryParam("longitude", place.longitude())
                            .queryParam("current", "temperature_2m,wind_speed_10m,weather_code")
                            .queryParam("timezone", "auto").build())
                    .retrieve().body(ForecastResponse.class);
            if (forecast == null || forecast.current() == null) {
                return "Für '" + place.name() + "' liegen gerade keine Wetterdaten vor.";
            }
            Current c = forecast.current();
            String country = place.country() == null ? "" : ", " + place.country();
            return "Wetter in " + place.name() + country + ": " + describe(c.weather_code()) + ", "
                    + Math.round(c.temperature_2m() * 10) / 10.0 + " °C, Wind "
                    + Math.round(c.wind_speed_10m()) + " km/h.";
        } catch (RestClientException e) {
            log.warn("Wetterabfrage für '{}' fehlgeschlagen", city, e);
            return "Der Wetterdienst ist gerade nicht erreichbar (" + e.getMessage() + ").";
        }
    }

    /** WMO-Wettercode → deutsche Beschreibung. */
    static String describe(int code) {
        return switch (code) {
            case 0 -> "klar";
            case 1 -> "überwiegend klar";
            case 2 -> "teilweise bewölkt";
            case 3 -> "bedeckt";
            case 45, 48 -> "Nebel";
            case 51, 53, 55 -> "Nieselregen";
            case 56, 57 -> "gefrierender Nieselregen";
            case 61 -> "leichter Regen";
            case 63 -> "Regen";
            case 65 -> "starker Regen";
            case 66, 67 -> "gefrierender Regen";
            case 71 -> "leichter Schneefall";
            case 73 -> "Schneefall";
            case 75 -> "starker Schneefall";
            case 77 -> "Schneegriesel";
            case 80, 81, 82 -> "Regenschauer";
            case 85, 86 -> "Schneeschauer";
            case 95 -> "Gewitter";
            case 96, 99 -> "Gewitter mit Hagel";
            default -> "unbekannte Wetterlage (Code " + code + ")";
        };
    }
}
