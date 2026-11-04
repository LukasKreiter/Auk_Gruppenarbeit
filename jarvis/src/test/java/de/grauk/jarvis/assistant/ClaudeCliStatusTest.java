package de.grauk.jarvis.assistant;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCliStatusTest {

    @Test
    void exitZeroMeansLoggedInAndAsksTheCliOnlyOnce() {
        FakeClaudeCli cli = FakeClaudeCli.printing(0, "{}");
        AtomicInteger starts = new AtomicInteger();
        cli.onStart(c -> starts.incrementAndGet());
        ClaudeCliStatus status = new ClaudeCliStatus(cli);

        assertThat(status.ready()).isTrue();
        assertThat(status.ready()).isTrue();

        assertThat(cli.command).endsWith("auth", "status");
        assertThat(starts).hasValue(1);
    }

    @Test
    void nonZeroExitMeansNotLoggedIn() {
        assertThat(new ClaudeCliStatus(FakeClaudeCli.printing(1, "{}")).ready()).isFalse();
    }

    @Test
    void cliThatCannotBeStartedIsNotReady() {
        assertThat(new ClaudeCliStatus(new ClaudeCliLauncher(new ClaudeCliProperties("jarvis-gibt-es-nicht", null)))
                .ready()).isFalse();
    }

    @Test
    void credentialsNeverReachTheCli() {
        Map<String, String> environment = new HashMap<>(Map.of("ANTHROPIC_API_KEY", "x", "ANTHROPIC_AUTH_TOKEN", "y",
                "PATH", "p"));

        ClaudeCliLauncher.removeCredentials(environment);

        assertThat(environment).containsOnlyKeys("PATH");
    }
}
