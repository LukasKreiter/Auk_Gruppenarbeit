package de.grauk.jarvis.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

class ToolRegistryTest {

    static class SampleTools {
        final AtomicInteger safeCalls = new AtomicInteger();
        final AtomicInteger dangerousCalls = new AtomicInteger();

        @Tool(description = "harmlos")
        public String safe() {
            safeCalls.incrementAndGet();
            return "safe-ok";
        }

        @Tool(description = "gefährlich")
        @RequiresConfirmation
        public String dangerous() {
            dangerousCalls.incrementAndGet();
            return "dangerous-ok";
        }

        @Tool(description = "kaputt")
        public String broken() {
            throw new IllegalStateException("boom");
        }
    }

    static class RecordingListener implements ToolInvocationListener {
        final List<String> events = new ArrayList<>();
        final CompletableFuture<UUID> confirmation = new CompletableFuture<>();

        @Override
        public void onToolCalled(String toolName, String argumentsJson) {
            events.add("called:" + toolName);
        }

        @Override
        public void onConfirmationRequired(UUID requestId, String toolName, String argumentsJson) {
            events.add("confirm:" + toolName);
            confirmation.complete(requestId);
        }

        @Override
        public void onToolCompleted(String toolName, String argumentsJson, String result) {
            events.add("completed:" + toolName + "=" + result);
        }
    }

    private final Map<String, ToolSetting> store = new LinkedHashMap<>();
    private ToolSettingRepository repository;
    private SampleTools tools;
    private ConfirmationGate gate;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        repository = mock(ToolSettingRepository.class);
        when(repository.findAll()).thenAnswer(inv -> new ArrayList<>(store.values()));
        when(repository.save(any(ToolSetting.class))).thenAnswer(inv -> {
            ToolSetting s = inv.getArgument(0);
            store.put(s.getToolName(), s);
            return s;
        });
        tools = new SampleTools();
        gate = new ConfirmationGate(props(Duration.ofSeconds(5)));
        registry = new ToolRegistry(() -> List.of(tools), repository, gate);
    }

    private static ToolsProperties props(Duration timeout) {
        return new ToolsProperties(timeout, null, null);
    }

    private List<String> enabledNames() {
        return registry.enabledCallbacks().stream().map(c -> c.getToolDefinition().name()).toList();
    }

    private ToolCallback callback(String name) {
        return registry.enabledCallbacks().stream()
                .filter(c -> c.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }

    private static ToolContext context(ToolInvocationListener listener) {
        return new ToolContext(Map.of(ToolInvocationListener.CONTEXT_KEY, listener));
    }

    @Test
    void createsMissingRowsWithDefaults() {
        registry.all();

        assertThat(store).containsOnlyKeys("safe", "dangerous", "broken");
        assertThat(store.get("safe").isEnabled()).isTrue();
        assertThat(store.get("safe").isRequiresConfirmation()).isFalse();
        assertThat(store.get("dangerous").isEnabled()).isTrue();
        assertThat(store.get("dangerous").isRequiresConfirmation()).isTrue();
    }

    @Test
    void doesNotOverwriteExistingRows() {
        store.put("dangerous", new ToolSetting("dangerous", false, false));

        registry.all();

        assertThat(store.get("dangerous").isEnabled()).isFalse();
        assertThat(store.get("dangerous").isRequiresConfirmation()).isFalse();
        assertThat(store).containsKeys("safe", "broken");
    }

    @Test
    void disabledToolIsNotInEnabledCallbacks() {
        store.put("safe", new ToolSetting("safe", false, false));

        assertThat(enabledNames()).containsExactlyInAnyOrder("dangerous", "broken");
    }

    @Test
    void toggleTakesEffectWithoutRestart() {
        assertThat(enabledNames()).contains("safe");

        registry.update("safe", false, false);
        assertThat(enabledNames()).doesNotContain("safe");

        registry.update("safe", true, false);
        assertThat(enabledNames()).contains("safe");
    }

    @Test
    void updateUnknownToolThrows() {
        assertThatThrownBy(() -> registry.update("gibtsNicht", true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allExposesDescriptionAndFlags() {
        registry.update("safe", false, true);

        assertThat(registry.all()).contains(new ToolRegistry.ToolInfo("safe", "harmlos", false, true));
    }

    @Test
    void requiresConfirmationIsAFloor() {
        assertThatThrownBy(() -> registry.update("dangerous", true, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("erfordert immer eine Bestätigung");

        store.put("dangerous", new ToolSetting("dangerous", true, false));
        assertThat(registry.all()).contains(new ToolRegistry.ToolInfo("dangerous", "gefährlich", true, true));

        RecordingListener listener = new RecordingListener();
        ToolCallback callback = callback("dangerous");
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> callback.call("{}", context(listener)));
        gate.resolve(listener.confirmation.join(), false);
        result.join();
        assertThat(tools.dangerousCalls).hasValue(0);
    }

    @Test
    void duplicateToolNamesFailFast() {
        registry = new ToolRegistry(() -> List.of(tools, new SampleTools()), repository, gate);

        assertThatThrownBy(registry::all).isInstanceOf(IllegalStateException.class).hasMessageContaining("Doppelter");
    }

    @Test
    void listenerReceivesCalledAndCompleted() {
        RecordingListener listener = new RecordingListener();

        String result = callback("safe").call("{}", context(listener));

        assertThat(result).contains("safe-ok");
        assertThat(listener.events).hasSize(2);
        assertThat(listener.events.get(0)).isEqualTo("called:safe");
        assertThat(listener.events.get(1)).startsWith("completed:safe=").contains("safe-ok");
    }

    @Test
    void failingToolIsReportedAsCompletedWithError() {
        RecordingListener listener = new RecordingListener();

        String result = callback("broken").call("{}", context(listener));

        assertThat(result).contains("boom");
        assertThat(listener.events).last().asString().startsWith("completed:broken=").contains("boom");
    }

    @Test
    void confirmedToolRunsAfterApproval() throws Exception {
        RecordingListener listener = new RecordingListener();
        ToolCallback callback = callback("dangerous");

        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> callback.call("{}", context(listener)));
        gate.resolve(listener.confirmation.get(), true);

        assertThat(result.get()).contains("dangerous-ok");
        assertThat(tools.dangerousCalls).hasValue(1);
        assertThat(listener.events).contains("called:dangerous", "confirm:dangerous");
    }

    @Test
    void deniedToolIsNotExecuted() throws Exception {
        RecordingListener listener = new RecordingListener();
        ToolCallback callback = callback("dangerous");

        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> callback.call("{}", context(listener)));
        gate.resolve(listener.confirmation.get(), false);

        assertThat(result.get()).contains("abgelehnt");
        assertThat(tools.dangerousCalls).hasValue(0);
        assertThat(listener.events).last().asString().startsWith("completed:dangerous=");
    }

    @Test
    void timeoutDoesNotExecuteTool() {
        gate = new ConfirmationGate(props(Duration.ofMillis(100)));
        registry = new ToolRegistry(() -> List.of(tools), repository, gate);
        RecordingListener listener = new RecordingListener();

        String result = callback("dangerous").call("{}", context(listener));

        assertThat(result).contains("nicht rechtzeitig");
        assertThat(tools.dangerousCalls).hasValue(0);
    }

    @Test
    void confirmationToolWithoutListenerIsNotExecuted() {
        String result = callback("dangerous").call("{}", new ToolContext(Map.of()));

        assertThat(result).contains("nicht ausgeführt");
        assertThat(tools.dangerousCalls).hasValue(0);
    }

    @Test
    void resolveUnknownIdThrows() {
        assertThatThrownBy(() -> gate.resolve(UUID.randomUUID(), true))
                .isInstanceOf(ConfirmationNotFoundException.class);
    }

    @Test
    void resolveAfterTimeoutThrows() {
        gate = new ConfirmationGate(props(Duration.ofMillis(50)));
        RecordingListener listener = new RecordingListener();

        gate.execute("x", "{}", listener, () -> "run");

        assertThatThrownBy(() -> gate.resolve(listener.confirmation.join(), true))
                .isInstanceOf(ConfirmationNotFoundException.class);
    }
}
