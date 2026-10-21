package de.grauk.jarvis.assistant;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;

/** Die explizit gebauten ChatClients; ein Provider fehlt, wenn sein ChatModel nicht als Bean existiert. */
public record ChatClients(Map<ChatProvider, ChatClient> byProvider) {

    public ChatClients {
        byProvider = Map.copyOf(byProvider);
    }
}
