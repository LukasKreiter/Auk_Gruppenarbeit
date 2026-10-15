package de.grauk.jarvis.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Server-Events fürs Dashboard (Events {@code ready}, {@code timer}); siehe {@link DashboardEvents}. */
@RestController
public class EventsController {

    private final DashboardEvents events;

    public EventsController(DashboardEvents events) {
        this.events = events;
    }

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return events.subscribe();
    }
}
