package de.grauk.jarvis.status;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Logback-Appender, der die letzten {@value #MAX_LINES} formatierten Logzeilen in einem statischen, thread-sicheren
 * Ringpuffer hält (Quelle der Status-Seite, {@code /fragments/logs}). Eingebunden über {@code logback-spring.xml}.
 *
 * <p>Der Puffer ist bewusst statisch: Logback instanziiert den Appender selbst, Spring-Beans greifen über
 * {@link #getLines()} darauf zu. Ein Fehler beim Formatieren wird nie nach außen geworfen, damit das Logging
 * nicht durch den Appender beeinträchtigt wird.
 *
 * <p>Das Pattern ist über {@link #setPattern(String)} (XML-Element {@code <pattern>}) konfigurierbar. Die Zeile
 * enthält Zeit, Level, Logger (gekürzt), Meldung und bei Exceptions die Kurzform (erste Zeile).
 */
public class LogBufferAppender extends AppenderBase<ILoggingEvent> {

    /** Maximale Anzahl gehaltener Zeilen. */
    public static final int MAX_LINES = 200;

    /** Standard-Pattern: Zeit, Level, Logger (kurz), Meldung, Exception-Kurzform. */
    public static final String DEFAULT_PATTERN = "%d{HH:mm:ss.SSS} %-5level %logger{20} : %msg%n%ex{short}";

    /** Eine gepufferte Logzeile; {@code text} ist die komplette, formatierte Zeile ohne abschließenden Zeilenumbruch. */
    public record LogLine(long timestamp, Level level, String text) {
    }

    private static final Deque<LogLine> BUFFER = new ArrayDeque<>(MAX_LINES + 1);
    private static final Object LOCK = new Object();

    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private final AtomicBoolean errorReported = new AtomicBoolean();
    private boolean counted;
    private String pattern = DEFAULT_PATTERN;
    private PatternLayout layout;

    /** Setzt das Layout-Pattern; wirkt nur vor {@link #start()}. */
    public void setPattern(String pattern) {
        if (pattern != null && !pattern.isBlank()) {
            this.pattern = pattern;
        }
    }

    public String getPattern() {
        return pattern;
    }

    @Override
    public void start() {
        try {
            PatternLayout l = new PatternLayout();
            l.setContext(getContext());
            l.setPattern(pattern);
            l.start();
            this.layout = l;
            super.start();
            if (!counted) {
                counted = true;
                ACTIVE.incrementAndGet();
            }
        } catch (RuntimeException e) {
            addError("LogBufferAppender konnte nicht gestartet werden", e);
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            String text = layout.doLayout(event).stripTrailing();
            LogLine line = new LogLine(event.getTimeStamp(), event.getLevel(), text);
            synchronized (LOCK) {
                if (BUFFER.size() >= MAX_LINES) {
                    BUFFER.pollFirst();
                }
                BUFFER.addLast(line);
            }
        } catch (RuntimeException e) {
            // Logging darf durch den Appender nie kaputtgehen; den ersten Fehler melden wir über den StatusManager
            if (errorReported.compareAndSet(false, true)) {
                try {
                    addError("LogBufferAppender: Logzeile konnte nicht gepuffert werden (weitere Fehler werden nicht gemeldet)", e);
                } catch (RuntimeException ignored) {
                    // auch die Meldung darf nichts kaputtmachen
                }
            }
        }
    }

    /**
     * {@code true}, solange mindestens ein Appender dieser Klasse erfolgreich gestartet ist. Ist er {@code false},
     * bleibt der Puffer leer (z. B. {@code logback-spring.xml} fehlt oder Start fehlgeschlagen).
     */
    public static boolean isActive() {
        return ACTIVE.get() > 0;
    }

    @Override
    public void stop() {
        if (isStarted() && counted) {
            counted = false;
            ACTIVE.decrementAndGet();
        }
        super.stop();
    }

    /** Alle gepufferten Zeilen, älteste zuerst (Kopie). */
    public static List<LogLine> getLines() {
        synchronized (LOCK) {
            return new ArrayList<>(BUFFER);
        }
    }

    /** Zeilen mit mindestens dem gegebenen Level (null = alle), älteste zuerst. */
    public static List<LogLine> getLines(Level minLevel) {
        if (minLevel == null) {
            return getLines();
        }
        synchronized (LOCK) {
            return BUFFER.stream().filter(l -> l.level().isGreaterOrEqual(minLevel)).toList();
        }
    }

    /** Leert den Puffer. */
    public static void clear() {
        synchronized (LOCK) {
            BUFFER.clear();
        }
    }
}
