package de.grauk.jarvis.web;

import de.grauk.jarvis.status.StatusService;
import de.grauk.jarvis.status.StatusService.StatusReport;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/status}: dieselben Daten wie das Status-Fragment, als JSON. */
@RestController
public class StatusController {

    private final StatusService status;

    public StatusController(StatusService status) {
        this.status = status;
    }

    @GetMapping("/api/status")
    public StatusReport status() {
        return status.report();
    }
}
