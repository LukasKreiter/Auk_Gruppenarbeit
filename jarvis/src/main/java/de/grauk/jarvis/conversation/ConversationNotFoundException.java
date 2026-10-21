package de.grauk.jarvis.conversation;

/** Die Konversation gibt es nicht (mehr); web/ bildet das auf 404 ab. */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(long conversationId) {
        super("Konversation " + conversationId + " nicht gefunden");
    }
}
