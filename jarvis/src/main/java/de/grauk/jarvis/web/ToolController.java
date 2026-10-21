package de.grauk.jarvis.web;

import java.util.List;
import java.util.UUID;

import de.grauk.jarvis.tools.ConfirmationGate;
import de.grauk.jarvis.tools.ToolRegistry;
import de.grauk.jarvis.tools.ToolRegistry.ToolInfo;
import de.grauk.jarvis.web.Dtos.ToolConfirmation;
import de.grauk.jarvis.web.Dtos.ToolUpdateRequest;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tool-Einstellungen und Freigabe bestätigungspflichtiger Tool-Aufrufe. */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final ConfirmationGate gate;

    public ToolController(ToolRegistry toolRegistry, ConfirmationGate gate) {
        this.toolRegistry = toolRegistry;
        this.gate = gate;
    }

    @GetMapping
    public List<ToolInfo> list() {
        return toolRegistry.all();
    }

    /** @throws IllegalArgumentException unbekanntes Tool oder Bestätigung bei einem Tool, das sie braucht, abgeschaltet (400) */
    @PutMapping("/{name}")
    public ToolInfo update(@PathVariable String name, @Valid @RequestBody ToolUpdateRequest request) {
        toolRegistry.update(name, request.enabled(), request.requiresConfirmation());
        return toolRegistry.all().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Tool: " + name));
    }

    /**
     * Antwort des Nutzers auf ein {@code confirm}-Event.
     *
     * @throws de.grauk.jarvis.tools.ConfirmationNotFoundException unbekannte oder abgelaufene Id (404)
     */
    @PostMapping("/confirmations/{id}")
    public ResponseEntity<Void> confirm(@PathVariable UUID id, @Valid @RequestBody ToolConfirmation request) {
        gate.resolve(id, request.approved());
        return ResponseEntity.noContent().build();
    }
}
