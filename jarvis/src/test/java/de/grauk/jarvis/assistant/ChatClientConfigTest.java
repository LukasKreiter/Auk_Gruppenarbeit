package de.grauk.jarvis.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prüft die Verdrahtung im echten Kontext (ohne Ollama/Anthropic; kein API-Key gesetzt). */
@SpringBootTest(properties = "spring.ai.anthropic.api-key=")
class ChatClientConfigTest {

    @Autowired
    ChatClients clients;

    @Autowired
    ChatProviderRegistry registry;

    @Test
    void ollamaClientExistsAndClaudeIsUnavailableWithoutKey() {
        assertThat(clients.byProvider()).containsKey(ChatProvider.OLLAMA);
        assertThat(registry.available().get(ChatProvider.ANTHROPIC)).isFalse();
        assertThatThrownBy(() -> registry.clientFor(ChatProvider.ANTHROPIC))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(clients.byProvider()).containsKey(ChatProvider.ANTHROPIC); // Bean existiert auch ohne Key
    }
}
