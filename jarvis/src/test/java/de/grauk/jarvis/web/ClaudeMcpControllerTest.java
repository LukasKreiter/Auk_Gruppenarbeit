package de.grauk.jarvis.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import de.grauk.jarvis.assistant.ClaudeToolBridge;
import de.grauk.jarvis.assistant.ClaudeToolBridge.ToolSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Läuft samt {@link LocalOnlyFilter}: die Anfragen kommen wie von der CLI ohne Origin-Header. */
@WebMvcTest(ClaudeMcpController.class)
@EnableConfigurationProperties(JarvisWebProperties.class)
class ClaudeMcpControllerTest {

    private static final String TOKEN = "token-des-laufs";

    @Autowired MockMvc mvc;
    @MockitoBean ClaudeToolBridge bridge;

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final ToolContext context = new ToolContext(Map.of("listener", "x"));

    private ToolCallback tool(String name) {
        ToolDefinition definition = DefaultToolDefinition.builder().name(name).description("Beschreibung")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"stadt\":{\"type\":\"string\"}}}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new AssertionError("ohne ToolContext aufgerufen");
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                assertThat(toolContext).isSameAs(context);
                calls.add(toolInput);
                return "Regen in Wien";
            }
        };
    }

    @BeforeEach
    void setUp() {
        Map<String, ToolCallback> tools = new LinkedHashMap<>();
        tools.put("getWeather", tool("getWeather"));
        when(bridge.find(TOKEN)).thenReturn(Optional.of(new ToolSession(tools, context)));
        when(bridge.find("falsch")).thenReturn(Optional.empty());
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post(ClaudeToolBridge.MCP_PATH).header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void rejectsMissingAndUnknownTokens() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

        mvc.perform(post(ClaudeToolBridge.MCP_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ClaudeToolBridge.MCP_PATH).header("Authorization", "Bearer falsch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void initializeTakesOverTheClientVersionAndAnnouncesTools() throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(0))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists())
                .andExpect(jsonPath("$.result.serverInfo.name").value("jarvis"));
    }

    @Test
    void notificationsAreAcceptedWithoutBody() throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void listsTheToolsOfThisRunWithTheirSchema() throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"tools/list\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("a"))
                .andExpect(jsonPath("$.result.tools.length()").value(1))
                .andExpect(jsonPath("$.result.tools[0].name").value("getWeather"))
                .andExpect(jsonPath("$.result.tools[0].description").value("Beschreibung"))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.properties.stadt.type").value("string"));
    }

    @Test
    void callsTheToolWithArgumentsAndTheContextOfThisRun() throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"getWeather\",\"arguments\":{\"stadt\":\"Wien\"}}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value("Regen in Wien"));

        assertThat(calls).containsExactly("{\"stadt\":\"Wien\"}");
    }

    @Test
    void missingArgumentsBecomeAnEmptyObject() throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"getWeather\"}}")
                .andExpect(status().isOk());

        assertThat(calls).containsExactly("{}");
    }

    @Test
    void toolsOutsideThisRunCannotBeCalled() throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"openApplication\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));

        assertThat(calls).isEmpty();
    }

    @Test
    void unknownMethodsAreAnswered() throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/list\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
        send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isMap());
    }

    @Test
    void getIsNotOfferedBecauseThereIsNoServerStream() throws Exception {
        mvc.perform(get(ClaudeToolBridge.MCP_PATH)).andExpect(status().isMethodNotAllowed());
    }
}
