package de.grauk.jarvis.web;

import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.conversation.ConversationService;
import de.grauk.jarvis.knowledge.DocumentIngestionService;
import de.grauk.jarvis.knowledge.FactMemory;
import de.grauk.jarvis.status.StatusService;
import de.grauk.jarvis.tools.ToolRegistry;
import de.grauk.jarvis.web.Dtos.ConversationDto;
import de.grauk.jarvis.web.Dtos.DocumentDto;
import de.grauk.jarvis.web.Dtos.FactDto;
import de.grauk.jarvis.web.Dtos.MessageDto;
import de.grauk.jarvis.web.Dtos.SettingsView;

import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * Befüllt das Thymeleaf-{@link Model} für Seiten und Fragmente. Die Attributnamen sind der Vertrag mit dem
 * Frontend (siehe docs/CODE-INFRASTRUKTUR.md); beide Controller benutzen dieselben Methoden, damit eine Seite
 * und ihr Fragment dieselben Daten sehen.
 */
@Component
class ViewModels {

    private final ConversationService conversations;
    private final SettingsWebService settings;
    private final ToolRegistry tools;
    private final DocumentIngestionService documents;
    private final FactMemory facts;
    private final StatusService status;

    ViewModels(ConversationService conversations, SettingsWebService settings, ToolRegistry tools,
               DocumentIngestionService documents, FactMemory facts, StatusService status) {
        this.status = status;
        this.conversations = conversations;
        this.settings = settings;
        this.tools = tools;
        this.documents = documents;
        this.facts = facts;
    }

    /**
     * {@code conversations}: {@code List<ConversationDto>}, neueste Aktivität zuerst;
     * {@code activeConversationId}: {@code Long} oder {@code null}.
     */
    void conversationList(Model model, Long activeConversationId) {
        model.addAttribute("conversations", conversations.list().stream().map(ConversationDto::from).toList());
        model.addAttribute("activeConversationId", activeConversationId);
    }

    /**
     * {@code activeConversation}: {@code ConversationDto}; {@code messages}: {@code List<MessageDto>}
     * (älteste zuerst, inkl. {@code toolCalls}); dazu {@link #conversationList} und {@link #settings}.
     */
    void chat(Model model, long conversationId) {
        model.addAttribute("activeConversation", ConversationDto.from(conversations.get(conversationId)));
        model.addAttribute("messages", conversations.messages(conversationId).stream().map(MessageDto::from).toList());
        conversationList(model, conversationId);
        settings(model);
    }

    /**
     * {@code settings}: {@code SettingsDto}; {@code available}: {@code Map<ChatProvider,Boolean>};
     * {@code models}: {@code Map<ChatProvider,List<String>>}; {@code providers}: {@code ChatProvider[]};
     * {@code defaultSystemPrompt}: String; {@code cloudActive}: {@code true}, wenn Claude der aktive Provider ist.
     */
    void settings(Model model) {
        SettingsView view = settings.view();
        model.addAttribute("settings", view.settings());
        model.addAttribute("available", view.available());
        model.addAttribute("models", view.models());
        model.addAttribute("providers", ChatProvider.values());
        model.addAttribute("defaultSystemPrompt", view.defaultSystemPrompt());
        model.addAttribute("cloudActive", view.settings().provider() == ChatProvider.ANTHROPIC);
    }

    /** {@code tools}: {@code List<ToolRegistry.ToolInfo>} (name, description, enabled, requiresConfirmation). */
    void tools(Model model) {
        model.addAttribute("tools", tools.all());
    }

    /** {@code documents}: {@code List<DocumentDto>}, neueste zuerst. */
    void documents(Model model) {
        model.addAttribute("documents", documents.list().stream().map(DocumentDto::from).toList());
    }

    /** {@code facts}: {@code List<FactDto>}, neueste zuerst. */
    void facts(Model model) {
        model.addAttribute("facts", facts.list().stream().map(FactDto::from).toList());
    }

    /** {@code status}: {@code StatusService.StatusReport} (Ampeln, Modelle, GPU, CPU/RAM, Latenzen). */
    void status(Model model) {
        model.addAttribute("status", status.report());
    }
}
