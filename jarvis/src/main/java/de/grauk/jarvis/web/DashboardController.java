package de.grauk.jarvis.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.ui.Model;


/**
 * Serves the HUD-style dashboard at "/". Initial values come from
 * {@link DashboardMockData}; the htmx-driven refreshes on the page hit
 * {@link HTMXFragmentController}, which uses the same mock data so the
 * first paint and the live updates never disagree.
 */
@Controller
public class DashboardController {

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("status", DashboardMockData.status());
        model.addAttribute("model", DashboardMockData.model());
        model.addAttribute("system", DashboardMockData.system());
        model.addAttribute("tools", DashboardMockData.tools());
        model.addAttribute("logs", DashboardMockData.logs());
        model.addAttribute("ingestion", DashboardMockData.ingestion());
        model.addAttribute("detail", DashboardMockData.detail("conversations"));
        return "dashboard";
    }
}
