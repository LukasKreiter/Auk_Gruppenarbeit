package de.grauk.jarvis.web;

import java.util.Objects;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ProviderUnavailableException;
import de.grauk.jarvis.conversation.Conversation;
import de.grauk.jarvis.conversation.ConversationNotFoundException;
import de.grauk.jarvis.conversation.ConversationService;
import de.grauk.jarvis.knowledge.DocumentIngestionService;
import de.grauk.jarvis.knowledge.FactMemory;
import de.grauk.jarvis.knowledge.KnowledgeDocument;
import de.grauk.jarvis.knowledge.KnowledgeException;
import de.grauk.jarvis.tools.ToolRegistry;
import de.grauk.jarvis.web.Dtos.DocumentDto;
import de.grauk.jarvis.web.Dtos.SettingsRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * HTML-Fragmente für HTMX unter {@code /fragments/**}. Jeder Endpoint befüllt das Model und liefert einen
 * Thymeleaf-Fragmentnamen ({@code "datei :: fragment"}); die Templates unter {@code templates/fragments/} baut das
 * Frontend-Team. Zustandsändernde Endpoints liefern nach der Änderung das aktualisierte Fragment zurück.
 *
 * <p>Fehler (z. B. ungültige Eingabe) kommen als HTTP 200 mit dem Model-Attribut {@code error} (String, sonst nicht
 * gesetzt), weil HTMX 4xx-Antworten standardmäßig nicht in die Seite übernimmt.
 *
 * <p>Jede mutierende Methode fängt Fehler selbst ab (statt eines {@code @ExceptionHandler}, weil jeder Endpoint sein
 * eigenes Fragment samt Model neu befüllen muss): erwartbare Fehler (ungültige Eingabe, unbekannte Id) werden mit
 * {@code log.warn} ohne Stacktrace geloggt, unerwartete {@code RuntimeException}s (z. B. {@code DataAccessException},
 * {@code ProviderUnavailableException}) mit {@code log.error} samt Stacktrace; in beiden Fällen kommt das Fragment
 * mit {@code error} zurück. Nicht hier behandelt: {@code MaxUploadSizeExceededException} tritt schon beim Parsen des
 * Multipart-Requests vor dem Controller auf und wird global vom {@code ApiExceptionHandler} als 413 beantwortet.
 */
@Controller
@RequestMapping("/fragments")
public class HTMXFragmentController {

    private static final Logger log = LoggerFactory.getLogger(HTMXFragmentController.class);

    static final String CONVERSATION_LIST = "fragments/conversation-list :: list";
    static final String SETTINGS_FORM = "fragments/settings-form :: form";
    static final String TOOL_LIST = "fragments/tool-list :: list";
    static final String DOCUMENT_LIST = "fragments/document-list :: list";
    static final String FACT_LIST = "fragments/fact-list :: list";
    static final String STATUS = "fragments/status :: status";

    private final ViewModels views;
    private final ConversationService conversations;
    private final SettingsWebService settings;
    private final ToolRegistry tools;
    private final DocumentIngestionService documents;
    private final FactMemory facts;

    HTMXFragmentController(ViewModels views, ConversationService conversations, SettingsWebService settings,
                           ToolRegistry tools, DocumentIngestionService documents, FactMemory facts) {
        this.views = views;
        this.conversations = conversations;
        this.settings = settings;
        this.tools = tools;
        this.documents = documents;
        this.facts = facts;
    }

    // ---- Fehlerbehandlung ----

    /** Erwartbarer Fehler (Validierung, unbekannte Id): warn ohne Stacktrace, Meldung an die UI. */
    private void rejected(Model model, String context, RuntimeException e) {
        log.warn("{} abgelehnt: {}", context, e.getMessage());
        model.addAttribute("error", e.getMessage());
    }

    /** Unerwarteter Fehler: error mit Stacktrace; die UI bekommt nur eine allgemeine Meldung (ausser Provider-/Wissensfehler). */
    private void failed(Model model, String context, RuntimeException e) {
        log.error("{} fehlgeschlagen", context, e);
        model.addAttribute("error", e instanceof ProviderUnavailableException || e instanceof KnowledgeException
                ? Objects.toString(e.getMessage(), "Vorgang fehlgeschlagen")
                : "Vorgang fehlgeschlagen, Details im Log");
    }

    // ---- Konversationsliste ----

    /** Attribute: conversations, activeConversationId. */
    @GetMapping("/conversations")
    public String conversationList(@RequestParam(required = false) Long active, Model model) {
        views.conversationList(model, active);
        return CONVERSATION_LIST;
    }

    /** Legt eine Konversation an. Attribute wie {@link #conversationList}; {@code activeConversationId} = neue Id. */
    @PostMapping("/conversations")
    public String createConversation(Model model) {
        Conversation created = conversations.create(null);
        views.conversationList(model, created.getId());
        return CONVERSATION_LIST;
    }

    /** Parameter {@code title}. Attribute wie {@link #conversationList}; ggf. {@code error}. */
    @PutMapping("/conversations/{id}")
    public String renameConversation(@PathVariable long id, @RequestParam String title,
                                     @RequestParam(required = false) Long active, Model model) {
        try {
            conversations.rename(id, title);
        } catch (IllegalArgumentException | ConversationNotFoundException e) {
            rejected(model, "Umbenennen der Unterhaltung " + id, e);
        } catch (RuntimeException e) {
            failed(model, "Umbenennen der Unterhaltung " + id, e);
        }
        views.conversationList(model, active);
        return CONVERSATION_LIST;
    }

    /** Attribute wie {@link #conversationList}; war die gelöschte die aktive, ist {@code activeConversationId} null. */
    @DeleteMapping("/conversations/{id}")
    public String deleteConversation(@PathVariable long id, @RequestParam(required = false) Long active, Model model) {
        try {
            conversations.delete(id);
        } catch (ConversationNotFoundException e) {
            rejected(model, "Löschen der Unterhaltung " + id, e);
        } catch (RuntimeException e) {
            failed(model, "Löschen der Unterhaltung " + id, e);
        }
        views.conversationList(model, active != null && active == id ? null : active);
        return CONVERSATION_LIST;
    }

    // ---- Einstellungen ----

    /** Attribute: settings, available, models, providers, defaultSystemPrompt, cloudActive. */
    @GetMapping("/settings")
    public String settingsForm(Model model) {
        views.settings(model);
        return SETTINGS_FORM;
    }

    /**
     * Speichert das Formular (nicht gesendete Checkboxen = false, Stimmen leer = Standard).
     * Attribute wie {@link #settingsForm}; {@code saved=true} bei Erfolg, sonst {@code error} (gespeicherte Werte bleiben).
     */
    @PostMapping("/settings")
    public String saveSettings(@RequestParam ChatProvider provider, @RequestParam String model,
                               @RequestParam String systemPrompt, @RequestParam double temperature,
                               @RequestParam(defaultValue = "false") boolean thinkMode,
                               @RequestParam(defaultValue = "") String voiceDe,
                               @RequestParam(defaultValue = "") String voiceEn,
                               @RequestParam(defaultValue = "false") boolean ttsEnabled, Model ui) {
        try {
            settings.update(new SettingsRequest(provider, model, systemPrompt, temperature, thinkMode,
                    voiceDe, voiceEn, ttsEnabled));
            ui.addAttribute("saved", true);
        } catch (IllegalArgumentException e) {
            rejected(ui, "Speichern der Einstellungen", e);
        } catch (RuntimeException e) {
            failed(ui, "Speichern der Einstellungen", e);
        }
        views.settings(ui);
        return SETTINGS_FORM;
    }

    // ---- Tools ----

    /** Attribut: tools. */
    @GetMapping("/tools")
    public String toolList(Model model) {
        views.tools(model);
        return TOOL_LIST;
    }

    /** Parameter {@code enabled}, {@code requiresConfirmation}. Attribut: tools; ggf. {@code error}. */
    @PostMapping("/tools/{name}")
    public String updateTool(@PathVariable String name, @RequestParam(defaultValue = "false") boolean enabled,
                             @RequestParam(defaultValue = "false") boolean requiresConfirmation, Model model) {
        try {
            tools.update(name, enabled, requiresConfirmation);
        } catch (IllegalArgumentException e) {
            rejected(model, "Aktualisieren des Tools " + name, e);
        } catch (RuntimeException e) {
            failed(model, "Aktualisieren des Tools " + name, e);
        }
        views.tools(model);
        return TOOL_LIST;
    }

    // ---- Wissen ----

    /** Attribut: documents. */
    @GetMapping("/documents")
    public String documentList(Model model) {
        views.documents(model);
        return DOCUMENT_LIST;
    }

    /**
     * Multipart-Feld {@code file}. Attribute: documents; {@code uploaded} ({@code DocumentDto}, Status INDEXED oder
     * FAILED mit statusMessage); ggf. {@code error} (leere Datei, Status FAILED = statusMessage, Ausnahmen).
     */
    @PostMapping("/documents")
    public String uploadDocument(@RequestParam("file") MultipartFile file, Model model) {
        if (file.isEmpty()) {
            log.warn("Upload abgelehnt: Datei '{}' ist leer", file.getOriginalFilename());
            model.addAttribute("error", "Die Datei ist leer");
        } else {
            try {
                DocumentDto dto = DocumentDto.from(documents.ingest(file));
                model.addAttribute("uploaded", dto);
                if (dto.status() == KnowledgeDocument.Status.FAILED) {
                    log.warn("Indexierung von '{}' fehlgeschlagen: {}", dto.filename(), dto.statusMessage());
                    model.addAttribute("error", dto.statusMessage());
                }
            } catch (KnowledgeException | IllegalArgumentException e) {
                rejected(model, "Upload von '" + file.getOriginalFilename() + "'", e);
            } catch (RuntimeException e) {
                failed(model, "Upload von '" + file.getOriginalFilename() + "'", e);
            }
        }
        views.documents(model);
        return DOCUMENT_LIST;
    }

    /** Attribut: documents; ggf. {@code error} (unbekannte Id, Fehler). */
    @DeleteMapping("/documents/{id}")
    public String deleteDocument(@PathVariable long id, Model model) {
        try {
            if (documents.list().stream().map(KnowledgeDocument::getId).noneMatch(existing -> existing == id)) {
                log.warn("Löschen abgelehnt: Dokument {} nicht gefunden", id);
                model.addAttribute("error", "Dokument nicht gefunden");
            } else {
                documents.delete(id);
            }
        } catch (RuntimeException e) {
            failed(model, "Löschen des Dokuments " + id, e);
        }
        views.documents(model);
        return DOCUMENT_LIST;
    }

    /** Attribut: facts. */
    @GetMapping("/facts")
    public String factList(Model model) {
        views.facts(model);
        return FACT_LIST;
    }

    /** Attribut: facts; ggf. {@code error} (unbekannte Id, Fehler). */
    @DeleteMapping("/facts/{id}")
    public String deleteFact(@PathVariable long id, Model model) {
        try {
            if (facts.list().stream().noneMatch(fact -> fact.getId() == id)) {
                log.warn("Löschen abgelehnt: Fakt {} nicht gefunden", id);
                model.addAttribute("error", "Fakt nicht gefunden");
            } else {
                facts.delete(id);
            }
        } catch (RuntimeException e) {
            failed(model, "Löschen des Fakts " + id, e);
        }
        views.facts(model);
        return FACT_LIST;
    }

    // ---- Status ----

    /** Attribut: status ({@code StatusService.StatusReport}). Die Logs liefert {@code LogController}. */
    @GetMapping("/status")
    public String status(Model model) {
        views.status(model);
        return STATUS;
    }
}
