package de.grauk.jarvis.assistant;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Baut ChatClients, Chat-Verlauf und Advisors explizit (ADR 0002). Es gibt bewusst keinen
 * ChatClient.Builder aus der Autokonfiguration (spring.ai.chat.client.enabled=false), da zwei ChatModels existieren.
 */
@Configuration
@EnableConfigurationProperties({AssistantProperties.class, ClaudeCliProperties.class})
public class ChatClientConfig {

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository, AssistantProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.memoryWindow())
                .build();
    }

    @Bean
    MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    @Bean
    GuardedRagAdvisor ragAdvisor(VectorStore vectorStore,
                                 @Value("${jarvis.knowledge.similarity-threshold:0.6}") double similarityThreshold,
                                 @Value("${jarvis.knowledge.top-k:4}") int topK) {
        QuestionAnswerAdvisor advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().similarityThreshold(similarityThreshold).topK(topK).build())
                .promptTemplate(PromptTemplates.ragTemplate())
                .build();
        return new GuardedRagAdvisor(advisor);
    }

    @Bean
    ClaudeCliChatModel claudeCliChatModel(ClaudeCliLauncher launcher, ClaudeToolBridge bridge,
                                          ClaudeCliProperties properties, Environment environment) {
        // local.server.port kennt auch einen zufälligen Port; erst zur Laufzeit gesetzt, daher pro Aufruf gelesen
        return new ClaudeCliChatModel(launcher, bridge, properties, () -> environment.getProperty(
                "local.server.port", Integer.class, environment.getProperty("server.port", Integer.class, 8080)));
    }

    @Bean
    ChatClients chatClients(ObjectProvider<OllamaChatModel> ollama, ClaudeCliChatModel claude,
                            MessageChatMemoryAdvisor memoryAdvisor, GuardedRagAdvisor ragAdvisor) {
        Map<ChatProvider, ChatClient> clients = new EnumMap<>(ChatProvider.class);
        // Reihenfolge über getOrder(): Chat-Verlauf speichert den Originaltext, danach ergänzt RAG den Prompt
        Advisor[] advisors = {memoryAdvisor, ragAdvisor};
        OllamaChatModel o = ollama.getIfAvailable();
        if (o != null) {
            clients.put(ChatProvider.OLLAMA, ChatClient.builder(o).defaultAdvisors(advisors).build());
        }
        clients.put(ChatProvider.ANTHROPIC, ChatClient.builder(claude).defaultAdvisors(advisors).build());
        return new ChatClients(clients);
    }
}
