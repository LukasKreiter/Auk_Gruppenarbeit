package de.grauk.jarvis.conversation;

import org.springframework.stereotype.Repository;
import java.util.HashMap;
import java.util.Map;

@Repository
public class Repositories {

    private final Map<Long, Conversation> conversationStore = new HashMap<>();
    private long idCounter = 1;

    public Conversation saveConversation(Conversation conversation) {
        conversationStore.put(idCounter++, conversation);
        return conversation;
    }

    public Conversation findConversationById(Long id) {
        return conversationStore.get(id);
    }
}