package de.grauk.jarvis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import de.grauk.jarvis.status.LogBufferAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

@WebMvcTest(LogController.class)
@EnableConfigurationProperties(JarvisWebProperties.class)
class LogControllerTest {

    @Autowired MockMvc mvc;
    @Autowired LogController controller;

    private final LoggerContext context = new LoggerContext();
    private LogBufferAppender appender;

    @BeforeEach
    void setUp() {
        LogBufferAppender.clear();
        appender = new LogBufferAppender();
        appender.setContext(context);
        appender.start();
        log(Level.INFO, "info-zeile");
        log(Level.ERROR, "<b>fehler-zeile</b>");
    }

    @AfterEach
    void tearDown() {
        appender.stop();
        LogBufferAppender.clear();
    }

    private void log(Level level, String message) {
        Logger logger = context.getLogger("test");
        appender.doAppend(new LoggingEvent(Logger.FQCN, logger, level, message, null, null));
    }

    @Test
    void apiReturnsAllLines() throws Exception {
        mvc.perform(get("/api/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].level").value("INFO"))
                .andExpect(jsonPath("$[0].text").value(containsString("info-zeile")))
                .andExpect(jsonPath("$[0].time").isNotEmpty());
    }

    @Test
    void apiFiltersByLevelCaseInsensitive() throws Exception {
        mvc.perform(get("/api/logs").param("level", "error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].level").value("ERROR"));
    }

    @Test
    void apiRejectsInvalidLevelWith400() throws Exception {
        mvc.perform(get("/api/logs").param("level", "LAUT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void fragmentSetsLinesAndLevel() {
        Model model = new ExtendedModelMap();

        String view = controller.logFragment("warn", model);

        assertThat(view).isEqualTo("fragments/logs :: list");
        assertThat(model.getAttribute("level")).isEqualTo("WARN");
        assertThat((List<?>) model.getAttribute("lines")).hasSize(1);
        assertThat(model.containsAttribute("error")).isFalse();
    }

    @Test
    void fragmentWithoutLevelShowsAll() {
        Model model = new ExtendedModelMap();

        controller.logFragment(null, model);

        assertThat(model.getAttribute("level")).isEqualTo("");
        assertThat((List<?>) model.getAttribute("lines")).hasSize(2);
    }

    @Test
    void fragmentWithInvalidLevelSetsErrorInsteadOfFailing() {
        Model model = new ExtendedModelMap();

        String view = controller.logFragment("LAUT", model);

        assertThat(view).isEqualTo("fragments/logs :: list");
        assertThat((String) model.getAttribute("error")).contains("LAUT");
        assertThat((List<?>) model.getAttribute("lines")).isEmpty();
    }
}
