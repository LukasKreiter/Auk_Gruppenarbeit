package de.grauk.jarvis.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * One mapping per dashboard panel. Each returns a Thymeleaf fragment
 * (defined in {@code fragments.html}) rather than a full page, which is
 * what lets htmx swap just that panel's markup in place.
 */
@Controller
@RequestMapping("/fragments")
public class HTMXFragmentController {

    @GetMapping("/status")
    public String status(Model model) {
        model.addAttribute("status", DashboardMockData.status());
        return "fragments :: statusPanelContent";
    }

    @GetMapping("/model")
    public String model(Model model) {
        model.addAttribute("model", DashboardMockData.model());
        return "fragments :: modelPanelContent";
    }

    @GetMapping("/system")
    public String system(Model model) {
        model.addAttribute("system", DashboardMockData.system());
        return "fragments :: systemPanelContent";
    }

    @GetMapping("/tools")
    public String tools(Model model) {
        model.addAttribute("tools", DashboardMockData.tools());
        return "fragments :: toolsPanelContent";
    }

    @PostMapping("/tools/{id}/toggle")
    public String toggleTool(@PathVariable String id, Model model) {
        DashboardMockData.toggleTool(id);
        model.addAttribute("tools", DashboardMockData.tools());
        return "fragments :: toolsPanelContent";
    }

    @GetMapping("/logs")
    public String logs(Model model) {
        model.addAttribute("logs", DashboardMockData.logs());
        return "fragments :: logsPanelContent";
    }

    @GetMapping("/ingestion")
    public String ingestion(Model model) {
        model.addAttribute("ingestion", DashboardMockData.ingestion());
        return "fragments :: ingestionPanelContent";
    }

    @GetMapping("/detail/{key}")
    public String detail(@PathVariable String key, Model model) {
        model.addAttribute("detail", DashboardMockData.detail(key));
        return "fragments :: detailPanelContent";
    }

    @PostMapping("/chat")
    public String chat(@RequestParam String message, Model model) {
        model.addAttribute("userMessage", message);
        model.addAttribute("reply", DashboardMockData.reply(message));
        return "fragments :: chatTurn";
    }
}
