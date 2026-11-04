package de.grauk.jarvis.assistant;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Ersetzt den CLI-Prozess: merkt sich den Aufruf und liefert vorgegebene Ausgabe. Es startet nie eine echte CLI. */
class FakeClaudeCli extends ClaudeCliLauncher {

    private final FakeProcess process;
    private final IOException startFailure;
    private Consumer<FakeClaudeCli> onStart = cli -> { };

    List<String> command;
    Path workDir;
    String systemPrompt;
    String mcpConfig;
    String mcpToolTimeout;

    private FakeClaudeCli(FakeProcess process, IOException startFailure) {
        super(new ClaudeCliProperties("claude", null));
        this.process = process;
        this.startFailure = startFailure;
    }

    /** Prozess, der die Zeilen ausgibt und mit {@code exitCode} endet. */
    static FakeClaudeCli printing(int exitCode, String... lines) {
        String stdout = String.join("\n", lines) + "\n";
        return new FakeClaudeCli(new FakeProcess(new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8)),
                exitCode, null), null);
    }

    /** Prozess, der hängt, bis er abgebrochen wird. */
    static FakeClaudeCli hanging() throws IOException {
        PipedOutputStream writer = new PipedOutputStream();
        return new FakeClaudeCli(new FakeProcess(new PipedInputStream(writer), 137, writer), null);
    }

    static FakeClaudeCli failingToStart() {
        return new FakeClaudeCli(null, new IOException("CreateProcess error=2"));
    }

    FakeClaudeCli onStart(Consumer<FakeClaudeCli> callback) {
        this.onStart = callback;
        return this;
    }

    FakeProcess process() {
        return process;
    }

    @Override
    Process start(ProcessBuilder builder) throws IOException {
        command = builder.command();
        mcpToolTimeout = builder.environment().get("MCP_TOOL_TIMEOUT");
        if (startFailure != null) {
            workDir = builder.directory().toPath();
            throw startFailure;
        }
        if (builder.directory() != null) {
            workDir = builder.directory().toPath();
            systemPrompt = Files.readString(workDir.resolve("system-prompt.txt"));
            Path mcp = workDir.resolve("mcp.json");
            mcpConfig = Files.exists(mcp) ? Files.readString(mcp) : null;
        }
        onStart.accept(this);
        return process;
    }

    static final class FakeProcess extends Process {

        private final InputStream stdout;
        private final OutputStream closeOnDestroy;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final CountDownLatch exited = new CountDownLatch(1);
        private final int exitCode;
        private volatile boolean destroyed;

        FakeProcess(InputStream stdout, int exitCode, OutputStream closeOnDestroy) {
            this.stdout = stdout;
            this.exitCode = exitCode;
            this.closeOnDestroy = closeOnDestroy;
            if (closeOnDestroy == null) {
                exited.countDown();
            }
        }

        String stdin() {
            return stdin.toString(StandardCharsets.UTF_8);
        }

        boolean destroyed() {
            return destroyed;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (isAlive()) {
                throw new IllegalThreadStateException();
            }
            return exitCode;
        }

        @Override
        public boolean isAlive() {
            return exited.getCount() > 0;
        }

        @Override
        public void destroy() {
            destroyForcibly();
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            try {
                if (closeOnDestroy != null) {
                    closeOnDestroy.close();
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            exited.countDown();
            return this;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }
}
