package de.grauk.jarvis.assistant;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;

import reactor.core.scheduler.Scheduler;

/**
 * Umschließt den QuestionAnswerAdvisor: Schlägt die Ähnlichkeitssuche fehl (z. B. Embedding-Server weg,
 * obwohl Claude als Provider gewählt ist), läuft die Anfrage ohne RAG-Kontext weiter (ADR 0003).
 * Der Fehler wird geloggt und über {@link #WARNING_SINK_KEY} als Warnung an die Anfrage gemeldet
 * (der Sink der Anfrage dedupliziert, da der ToolCallingAdvisor die Kette pro Tool-Runde neu durchläuft).
 */
public class GuardedRagAdvisor implements BaseAdvisor {

    /** Advisor-Parameter: {@code Consumer<String>}, der die Warnung an die laufende Anfrage weitergibt. */
    public static final String WARNING_SINK_KEY = "jarvis.ragWarningSink";

    static final String WARNING_TEXT = "Wissen und Gedächtnis sind gerade nicht verfügbar – Antwort ohne diesen Kontext.";

    private static final Logger log = LoggerFactory.getLogger(GuardedRagAdvisor.class);

    private final QuestionAnswerAdvisor delegate;

    public GuardedRagAdvisor(QuestionAnswerAdvisor delegate) {
        this.delegate = delegate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        try {
            return delegate.before(request, chain);
        } catch (RuntimeException e) {
            log.warn("RAG-Suche fehlgeschlagen, Anfrage läuft ohne Kontext weiter", e);
            if (request.context().get(WARNING_SINK_KEY) instanceof Consumer<?> sink) {
                ((Consumer<String>) sink).accept(WARNING_TEXT);
            }
            return request;
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return delegate.getOrder();
    }

    @Override
    public Scheduler getScheduler() {
        return delegate.getScheduler();
    }

    @Override
    public String getName() {
        return "GuardedRagAdvisor";
    }
}
