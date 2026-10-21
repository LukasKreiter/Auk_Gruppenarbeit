package de.grauk.jarvis.tools.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import de.grauk.jarvis.tools.ToolsProperties;

class WeatherToolsTest {

    private static final String GEO = "http://geo.test/search";
    private static final String FORECAST = "http://forecast.test/forecast";

    private MockRestServiceServer server;
    private WeatherTools tools;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ToolsProperties props = new ToolsProperties(Duration.ofSeconds(1),
                new ToolsProperties.Weather(GEO, FORECAST, Duration.ofSeconds(1)), null);
        tools = new WeatherTools(props, builder.build());
    }

    @Test
    void returnsWeatherAndEncodesCity() {
        server.expect(requestTo(GEO + "?name=Bad%20Homburg&count=1&language=de&format=json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"results":[{"name":"Bad Homburg","country":"Deutschland","latitude":50.2,"longitude":8.6,"id":1}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(FORECAST
                        + "?latitude=50.2&longitude=8.6&current=temperature_2m,wind_speed_10m,weather_code&timezone=auto"))
                .andRespond(withSuccess("""
                        {"current":{"temperature_2m":18.44,"wind_speed_10m":12.4,"weather_code":3}}
                        """, MediaType.APPLICATION_JSON));

        String result = tools.getWeather("Bad Homburg");

        assertThat(result).contains("Bad Homburg").contains("bedeckt").contains("18.4").contains("12 km/h");
        server.verify();
    }

    @Test
    void unknownCityGivesClearMessage() {
        server.expect(requestTo(GEO + "?name=Nirgendwo&count=1&language=de&format=json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(tools.getWeather("Nirgendwo")).contains("nicht gefunden");
    }

    @Test
    void serverErrorGivesMessageInsteadOfException() {
        server.expect(requestTo(GEO + "?name=Wien&count=1&language=de&format=json"))
                .andRespond(withServerError());

        assertThat(tools.getWeather("Wien")).contains("nicht erreichbar");
    }

    @Test
    void mapsWeatherCodes() {
        assertThat(WeatherTools.describe(0)).isEqualTo("klar");
        assertThat(WeatherTools.describe(95)).isEqualTo("Gewitter");
        assertThat(WeatherTools.describe(1234)).contains("unbekannt");
    }
}
