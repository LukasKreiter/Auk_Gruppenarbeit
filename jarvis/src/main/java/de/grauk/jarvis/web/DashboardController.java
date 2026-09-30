package de.grauk.jarvis.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    @GetMapping("/")
    public String dashboard() {
        return "chat";
    }

    @GetMapping("/chat.html")
    public String chat() {
        return "chat";
    }

    @GetMapping("/status")
    public String status() {
        return "status";
    }

    @GetMapping("/settings")
    public String settings() {
        return "settings";
    }

    @GetMapping("/tools")
    public String tools() {
        return "tools";
    }

    @GetMapping("/knowledge")
    public String knowledge() {
        return "knowledge";
    }
}