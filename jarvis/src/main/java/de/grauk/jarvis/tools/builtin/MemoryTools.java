package de.grauk.jarvis.tools.builtin;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import de.grauk.jarvis.knowledge.FactMemory;

@Component
public class MemoryTools {

    private static final Logger log = LoggerFactory.getLogger(MemoryTools.class);
    private static final int TOP_K = 4;

    private final FactMemory factMemory;

    public MemoryTools(FactMemory factMemory) {
        this.factMemory = factMemory;
    }

    @Tool(description = "Merkt sich eine Tatsache über den Nutzer dauerhaft (z. B. Vorlieben, Namen, Termine). "
            + "Verwenden, wenn der Nutzer sagt 'Merk dir ...' oder 'Denk daran, dass ...'. "
            + "Formuliere die Tatsache als kurzen, eigenständigen Satz.")
    public String rememberFact(@ToolParam(description = "Die zu merkende Tatsache als vollständiger Satz") String text) {
        if (text == null || text.isBlank()) {
            return "Es wurde nichts zum Merken angegeben.";
        }
        try {
            factMemory.remember(text.strip(), null);
            return "Gemerkt: " + text.strip();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            log.warn("Fakt konnte nicht gespeichert werden", e);
            return "Der Fakt konnte nicht gespeichert werden (LLM-Server erreichbar?): " + e.getMessage();
        }
    }

    @Tool(description = "Durchsucht das Gedächtnis (gemerkte Fakten) und die hochgeladenen Dokumente des Nutzers "
            + "nach relevanten Informationen. Verwenden, wenn die Frage persönliche Angaben oder Dokumentinhalte "
            + "betrifft, die du nicht kennst.")
    public String searchKnowledge(@ToolParam(description = "Suchanfrage in natürlicher Sprache") String query) {
        if (query == null || query.isBlank()) {
            return "Es wurde keine Suchanfrage angegeben.";
        }
        List<Document> hits;
        try {
            hits = factMemory.search(query.strip(), TOP_K);
        } catch (RuntimeException e) {
            log.warn("Wissenssuche fehlgeschlagen", e);
            return "Die Wissenssuche ist gerade nicht möglich (LLM-Server erreichbar?): " + e.getMessage();
        }
        if (hits == null || hits.isEmpty()) {
            return "Keine passenden Einträge gefunden.";
        }
        StringBuilder sb = new StringBuilder();
        for (Document doc : hits) {
            Object type = doc.getMetadata().get("type");
            sb.append("- ");
            if (type != null) {
                sb.append('[').append(type).append("] ");
            }
            sb.append(doc.getText()).append('\n');
        }
        return sb.toString().strip();
    }
}
