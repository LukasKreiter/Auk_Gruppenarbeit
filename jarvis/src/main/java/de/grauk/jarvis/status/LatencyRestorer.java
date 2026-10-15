package de.grauk.jarvis.status;

import de.grauk.jarvis.conversation.Message;
import de.grauk.jarvis.conversation.MessageRepository;
import de.grauk.jarvis.conversation.Role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Lädt beim Start die letzten Latenzen aus der Datenbank, damit die Statusseite nicht leer beginnt. */
@Component
class LatencyRestorer {

    private static final Logger log = LoggerFactory.getLogger(LatencyRestorer.class);

    private final MessageRepository messages;
    private final MetricsService metrics;

    LatencyRestorer(MessageRepository messages, MetricsService metrics) {
        this.messages = messages;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    void restore() {
        try {
            Long stt = messages.findFirstByRoleAndSttMsNotNullOrderByIdDesc(Role.USER).map(Message::getSttMs).orElse(null);
            Long llm = messages.findFirstByRoleAndLlmMsNotNullOrderByIdDesc(Role.ASSISTANT).map(Message::getLlmMs).orElse(null);
            Long tts = messages.findFirstByRoleAndTtsMsNotNullOrderByIdDesc(Role.ASSISTANT).map(Message::getTtsMs).orElse(null);
            metrics.restoreLatencies(stt, llm, tts);
        } catch (RuntimeException e) {
            log.warn("Letzte Latenzen konnten nicht geladen werden", e);
        }
    }
}
