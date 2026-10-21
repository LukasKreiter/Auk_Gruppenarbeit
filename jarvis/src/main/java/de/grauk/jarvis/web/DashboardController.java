package de.grauk.jarvis.web;

import de.grauk.jarvis.conversation.ConversationNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Ganze Seiten. Die Templates (templates/*.html) baut das Frontend-Team; die Model-Attribute je Seite
 * stehen im Javadoc von {@link ViewModels} und in docs/CODE-INFRASTRUKTUR.md.
 */
@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final ViewModels views;

    DashboardController(ViewModels views) {
        this.views = views;
    }

    /** View {@code chat}; Attribute: conversations, activeConversationId (null), settings, available, models, cloudActive. */
    @GetMapping({"/", "/chat.html"})
    public String dashboard(Model model) {
        views.conversationList(model, null);
        views.settings(model);
        return "chat";
    }

    /**
     * View {@code chat}; zusätzlich activeConversation und messages. Unbekannte Id: Weiterleitung auf {@code /}
     * mit Flash-Attribut {@code error} ("Unterhaltung nicht gefunden"), das auf der Zielseite als Model-Attribut
     * {@code error} ankommt.
     */
    @GetMapping("/chat/{id}")
    public String chat(@PathVariable long id, Model model, RedirectAttributes redirect) {
        try {
            views.chat(model, id);
        } catch (ConversationNotFoundException e) {
            log.info("Unterhaltung {} nicht gefunden, Weiterleitung auf /", id);
            redirect.addFlashAttribute("error", "Unterhaltung nicht gefunden");
            return "redirect:/";
        }
        return "chat";
    }

    /** View {@code status}; Attribut {@code status} ({@code StatusReport}), dazu wie {@code /settings}. Logs: {@code LogController}. */
    @GetMapping("/status")
    public String status(Model model) {
        views.settings(model);
        views.status(model);
        return "status";
    }

    /** View {@code settings}; Attribute: settings, available, models, providers, defaultSystemPrompt, cloudActive. */
    @GetMapping("/settings")
    public String settings(Model model) {
        views.settings(model);
        return "settings";
    }

    /** View {@code tools}; Attribut: tools. */
    @GetMapping("/tools")
    public String tools(Model model) {
        views.tools(model);
        return "tools";
    }

    /** View {@code knowledge}; Attribute: documents, facts. */
    @GetMapping("/knowledge")
    public String knowledge(Model model) {
        views.documents(model);
        views.facts(model);
        return "knowledge";
    }
}
