package de.grauk.jarvis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.UUID;

import de.grauk.jarvis.tools.TimerExpiredEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

class DashboardEventsTest {

    private DashboardEvents events;

    @AfterEach
    void tearDown() {
        if (events != null) {
            events.shutdown();
        }
    }

    @Test
    void limitsEmittersAndClosesTheOldest() {
        events = new DashboardEvents(JsonMapper.builder().build(), 3);

        for (int i = 0; i < 5; i++) {
            events.subscribe();
        }

        assertThat(events.subscriberCount()).isEqualTo(3);
    }

    @Test
    void heartbeatRemovesDeadEmitters() {
        events = new DashboardEvents(JsonMapper.builder().build(), 20);
        SseEmitter dead = events.subscribe();
        events.subscribe();
        assertThat(events.subscriberCount()).isEqualTo(2);

        dead.complete(); // ein beendeter Emitter wirft beim nächsten send() IllegalStateException
        events.heartbeat();

        assertThat(events.subscriberCount()).isEqualTo(1);
    }

    @Test
    void broadcastReportsDeliveredClientsAndRemovesDeadOnes() {
        events = new DashboardEvents(JsonMapper.builder().build(), 20);
        SseEmitter dead = events.subscribe();
        events.subscribe();
        dead.complete();

        int delivered = events.broadcast("timer", new DashboardEvents.TimerPayload(UUID.randomUUID(), "Tee", 3, Instant.now()));

        assertThat(delivered).isEqualTo(1);
        assertThat(events.subscriberCount()).isEqualTo(1);
    }

    @Test
    void timerWithoutAnyClientDoesNotFail() {
        events = new DashboardEvents(JsonMapper.builder().build(), 20);

        assertThatCode(() -> events.onTimerExpired(new TimerExpiredEvent(UUID.randomUUID(), "Tee", 3, Instant.now())))
                .doesNotThrowAnyException();
    }

    @Test
    void timerWithoutClientIsDeliveredToNextSubscriber() {
        events = new DashboardEvents(JsonMapper.builder().build(), 20);

        events.onTimerExpired(new TimerExpiredEvent(UUID.randomUUID(), "Tee", 3, Instant.now()));
        assertThat(events.pendingTimerCount()).isEqualTo(1);

        events.subscribe();
        assertThat(events.pendingTimerCount()).isZero();
    }

    @Test
    void pendingTimersAreBounded() {
        events = new DashboardEvents(JsonMapper.builder().build(), 20);

        for (int i = 0; i < DashboardEvents.MAX_PENDING_TIMERS + 5; i++) {
            events.onTimerExpired(new TimerExpiredEvent(UUID.randomUUID(), "T" + i, 1, Instant.now()));
        }

        assertThat(events.pendingTimerCount()).isEqualTo(DashboardEvents.MAX_PENDING_TIMERS);
    }
}
