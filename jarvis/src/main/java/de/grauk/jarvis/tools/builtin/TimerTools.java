package de.grauk.jarvis.tools.builtin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import de.grauk.jarvis.tools.TimerExpiredEvent;

@Component
public class TimerTools {

    private static final Logger log = LoggerFactory.getLogger(TimerTools.class);

    static final int MIN_MINUTES = 1;
    static final int MAX_MINUTES = 1440;

    private final TaskScheduler scheduler;
    private final ApplicationEventPublisher publisher;

    // Parametername = Beanname (ToolsConfig), falls andere Pakete ebenfalls einen TaskScheduler definieren
    public TimerTools(TaskScheduler toolTaskScheduler, ApplicationEventPublisher publisher) {
        this.scheduler = toolTaskScheduler;
        this.publisher = publisher;
    }

    @Tool(description = "Stellt einen Timer, der nach der angegebenen Anzahl Minuten abläuft und den Nutzer "
            + "benachrichtigt. Verwenden bei Wünschen wie 'Stell einen Timer auf 10 Minuten' oder "
            + "'Erinnere mich in einer Stunde'. Erlaubt sind 1 bis 1440 Minuten.")
    public String setTimer(
            @ToolParam(description = "Dauer in Minuten, ganze Zahl von 1 bis 1440") int minutes,
            @ToolParam(required = false, description = "Kurze Bezeichnung des Timers, z. B. 'Nudeln'") String label) {
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            return "Ungültige Dauer: " + minutes + " Minuten. Erlaubt sind " + MIN_MINUTES + " bis " + MAX_MINUTES + ".";
        }
        String name = label == null || label.isBlank() ? "Timer" : label.strip();
        UUID timerId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(minutes * 60L);
        scheduler.schedule(() -> {
            try {
                publisher.publishEvent(new TimerExpiredEvent(timerId, name, minutes, Instant.now()));
            } catch (RuntimeException e) {
                log.error("Timer '{}' konnte nicht gemeldet werden", name, e);
            }
        }, expiresAt);
        String time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(expiresAt);
        return "Timer '" + name + "' gestellt: läuft in " + minutes + " Minuten um " + time + " Uhr ab.";
    }
}
