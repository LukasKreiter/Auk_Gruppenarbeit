package de.grauk.jarvis.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import de.grauk.jarvis.status.LogBufferAppender.LogLine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogBufferAppenderTest {

    private final LoggerContext context = new LoggerContext();
    private final Logger logger = context.getLogger("de.grauk.jarvis.status.Test");
    private LogBufferAppender appender;

    @BeforeEach
    void setUp() {
        LogBufferAppender.clear();
        appender = new LogBufferAppender();
        appender.setContext(context);
        appender.start();
    }

    @AfterEach
    void tearDown() {
        appender.stop();
        LogBufferAppender.clear();
    }

    private void log(Level level, String message) {
        appender.doAppend(new LoggingEvent(Logger.FQCN, logger, level, message, null, null));
    }

    @Test
    void startsAndFormatsLine() {
        assertThat(appender.isStarted()).isTrue();
        log(Level.INFO, "Hallo Welt");

        List<LogLine> lines = LogBufferAppender.getLines();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).level()).isEqualTo(Level.INFO);
        assertThat(lines.get(0).text()).contains("INFO", "Hallo Welt").doesNotEndWith("\n");
    }

    @Test
    void exceptionIsAppendedInShortForm() {
        appender.doAppend(new LoggingEvent(Logger.FQCN, logger, Level.ERROR, "Fehler",
                new IllegalStateException("kaputt"), null));

        assertThat(LogBufferAppender.getLines().get(0).text()).contains("Fehler", "IllegalStateException");
    }

    @Test
    void keepsOnlyLast200Lines() {
        for (int i = 0; i <= LogBufferAppender.MAX_LINES; i++) {
            log(Level.INFO, "msg-" + i);
        }

        List<LogLine> lines = LogBufferAppender.getLines();
        assertThat(lines).hasSize(LogBufferAppender.MAX_LINES);
        assertThat(lines.get(0).text()).contains("msg-1").doesNotContain("msg-0");
        assertThat(lines.get(lines.size() - 1).text()).contains("msg-200");
    }

    @Test
    void filtersByMinimumLevel() {
        log(Level.DEBUG, "d");
        log(Level.INFO, "i");
        log(Level.WARN, "w");
        log(Level.ERROR, "e");

        assertThat(LogBufferAppender.getLines(Level.WARN)).extracting(LogLine::level)
                .containsExactly(Level.WARN, Level.ERROR);
        assertThat(LogBufferAppender.getLines(Level.TRACE)).hasSize(4);
        assertThat(LogBufferAppender.getLines(null)).hasSize(4);
    }

    @Test
    void formatErrorsNeverThrowAndAreReportedOnce() {
        ILoggingEvent broken = org.mockito.Mockito.mock(ILoggingEvent.class);
        org.mockito.Mockito.when(broken.getTimeStamp()).thenThrow(new IllegalStateException("kaputt"));
        org.mockito.Mockito.when(broken.getLevel()).thenThrow(new IllegalStateException("kaputt"));
        org.mockito.Mockito.when(broken.getFormattedMessage()).thenThrow(new IllegalStateException("kaputt"));

        appender.doAppend(broken);
        appender.doAppend(broken);

        assertThat(LogBufferAppender.getLines()).isEmpty();
        long errors = context.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getLevel() == ch.qos.logback.core.status.Status.ERROR)
                .filter(s -> s.getMessage().contains("LogBufferAppender"))
                .count();
        assertThat(errors).isEqualTo(1);
    }

    @Test
    void isActiveFollowsStartAndStop() {
        // Der Zähler ist statisch; andere Appender (z. B. aus logback-spring.xml in Spring-Kontexten) können ihn > 0 halten.
        assertThat(LogBufferAppender.isActive()).isTrue();
        appender.stop();
        boolean withoutThis = LogBufferAppender.isActive();
        appender.start();
        assertThat(LogBufferAppender.isActive()).isTrue();
        appender.stop();
        assertThat(LogBufferAppender.isActive()).isEqualTo(withoutThis);
    }

    @Test
    void clearEmptiesBuffer() {
        log(Level.INFO, "x");
        LogBufferAppender.clear();

        assertThat(LogBufferAppender.getLines()).isEmpty();
    }

    @Test
    void returnedListIsACopy() {
        log(Level.INFO, "x");
        List<LogLine> copy = LogBufferAppender.getLines();
        log(Level.INFO, "y");

        assertThat(copy).hasSize(1);
    }

    @Test
    void concurrentAppendsRespectLimit() throws Exception {
        int threads = 8;
        int perThread = 100;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    log(Level.INFO, "m");
                    LogBufferAppender.getLines(Level.INFO);
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000);
        }

        assertThat(LogBufferAppender.getLines()).hasSize(LogBufferAppender.MAX_LINES);
    }
}
