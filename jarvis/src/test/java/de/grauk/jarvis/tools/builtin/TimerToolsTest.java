package de.grauk.jarvis.tools.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import de.grauk.jarvis.tools.TimerExpiredEvent;

class TimerToolsTest {

    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final TimerTools tools = new TimerTools(scheduler, publisher);

    @Test
    void schedulesTimerAndPublishesEventOnExpiry() {
        Instant before = Instant.now();

        String result = tools.setTimer(10, "Nudeln");

        assertThat(result).contains("Nudeln").contains("10 Minuten");
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(task.capture(), when.capture());
        assertThat(when.getValue()).isAfterOrEqualTo(before.plusSeconds(600));
        verify(publisher, never()).publishEvent(any(Object.class));

        task.getValue().run();

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(TimerExpiredEvent.class, e -> {
            assertThat(e.label()).isEqualTo("Nudeln");
            assertThat(e.minutes()).isEqualTo(10);
            assertThat(e.timerId()).isNotNull();
        });
    }

    @Test
    void rejectsOutOfRangeMinutes() {
        assertThat(tools.setTimer(0, "x")).contains("Ungültige Dauer");
        assertThat(tools.setTimer(1441, "x")).contains("Ungültige Dauer");
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void blankLabelDefaultsToTimer() {
        assertThat(tools.setTimer(1, " ")).contains("'Timer'");
    }
}
