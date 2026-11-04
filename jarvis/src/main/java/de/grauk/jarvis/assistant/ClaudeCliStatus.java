package de.grauk.jarvis.assistant;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Prüft mit {@code claude auth status}, ob die CLI installiert und angemeldet ist (Exit 0). Kurz gecacht, die Statusseite pollt. */
@Component
class ClaudeCliStatus {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliStatus.class);
    private static final long CACHE_NANOS = Duration.ofSeconds(30).toNanos();
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);

    private final ClaudeCliLauncher launcher;

    private record Probe(boolean ready, long takenAtNanos) {}

    private volatile Probe probe;

    ClaudeCliStatus(ClaudeCliLauncher launcher) {
        this.launcher = launcher;
    }

    boolean ready() {
        Probe cached = probe;
        if (cached != null && System.nanoTime() - cached.takenAtNanos() < CACHE_NANOS) {
            return cached.ready();
        }
        boolean ready = check();
        probe = new Probe(ready, System.nanoTime());
        return ready;
    }

    private boolean check() {
        ProcessBuilder builder = launcher.command(List.of("auth", "status"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = launcher.start(builder);
            if (!process.waitFor(CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                log.warn("claude auth status antwortet nicht innerhalb von {}", CHECK_TIMEOUT);
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            log.info("Claude-CLI nicht startbar: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
