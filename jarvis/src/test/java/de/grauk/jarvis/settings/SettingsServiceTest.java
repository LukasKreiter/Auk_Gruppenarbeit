package de.grauk.jarvis.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import de.grauk.jarvis.assistant.AssistantProperties;
import de.grauk.jarvis.assistant.ChatProvider;
import de.grauk.jarvis.assistant.ChatProviderRegistry;
import de.grauk.jarvis.settings.SettingsService.SettingsSnapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SettingsServiceTest {

    private final AssistantSettingsRepository repository = mock(AssistantSettingsRepository.class);
    private final ChatProviderRegistry registry = mock(ChatProviderRegistry.class);
    private SettingsService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatProviderRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        when(registry.models(ChatProvider.OLLAMA)).thenReturn(List.of("qwen3:8b", "llama3.2"));
        when(registry.models(ChatProvider.ANTHROPIC)).thenReturn(List.of("claude-sonnet-5-5"));
        when(repository.findById(AssistantSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            AssistantSettings saved = inv.getArgument(0);
            when(repository.findById(AssistantSettings.SINGLETON_ID)).thenReturn(Optional.of(saved));
            return saved;
        });
        service = new SettingsService(repository,
                new AssistantProperties(ChatProvider.OLLAMA, List.of("claude-sonnet-5-5"), null), provider, "qwen3:8b");
    }

    private AssistantSettings form(ChatProvider provider, String model, double temperature) {
        return new AssistantSettings(provider, model, "Prompt", temperature, true, "de-voice", "en-voice", false);
    }

    @Test
    void firstAccessCreatesDefaults() {
        SettingsSnapshot current = service.current();

        assertThat(current).isEqualTo(new SettingsSnapshot(ChatProvider.OLLAMA, "qwen3:8b",
                SettingsService.DEFAULT_SYSTEM_PROMPT, 0.7, false));
        verify(repository).save(any());
    }

    @Test
    void currentIsCachedAfterFirstAccess() {
        service.current();
        service.current();

        verify(repository, times(1)).findById(AssistantSettings.SINGLETON_ID);
    }

    @Test
    void updateIsVisibleInCurrent() {
        service.update(form(ChatProvider.ANTHROPIC, "claude-sonnet-5-5", 0.3));

        assertThat(service.current()).isEqualTo(
                new SettingsSnapshot(ChatProvider.ANTHROPIC, "claude-sonnet-5-5", "Prompt", 0.3, true));
        assertThat(service.getVoiceFor("en-US")).isEqualTo("en-voice");
        assertThat(service.getVoiceFor("de")).isEqualTo("de-voice");
        assertThat(service.isTtsEnabled()).isFalse();
    }

    @Test
    void rejectsTemperatureOutOfRange() {
        assertThatThrownBy(() -> service.update(form(ChatProvider.OLLAMA, "qwen3:8b", 2.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(form(ChatProvider.ANTHROPIC, "claude-sonnet-5-5", 1.5)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsUnknownModel() {
        assertThatThrownBy(() -> service.update(form(ChatProvider.OLLAMA, "gibt-es-nicht", 0.7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gibt-es-nicht");
    }

    @Test
    void emptyModelListRejectsProviderOrModelChange() {
        service.update(form(ChatProvider.OLLAMA, "qwen3:8b", 0.7));
        when(registry.models(ChatProvider.OLLAMA)).thenReturn(List.of());
        when(registry.models(ChatProvider.ANTHROPIC)).thenReturn(List.of());

        assertThatThrownBy(() -> service.update(form(ChatProvider.OLLAMA, "neues-modell", 0.7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Modellliste von OLLAMA nicht abrufbar – Modell kann nicht geprüft werden");
        assertThatThrownBy(() -> service.update(form(ChatProvider.ANTHROPIC, "qwen3:8b", 0.7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ANTHROPIC");
        assertThat(service.current().model()).isEqualTo("qwen3:8b");
    }

    @Test
    void emptyModelListAllowsChangesWithoutModelChange() {
        service.update(form(ChatProvider.OLLAMA, "qwen3:8b", 0.7));
        when(registry.models(ChatProvider.OLLAMA)).thenReturn(List.of());

        AssistantSettings changed = form(ChatProvider.OLLAMA, "qwen3:8b", 1.1);
        changed.setSystemPrompt("Anderer Prompt");
        service.update(changed);

        assertThat(service.current().systemPrompt()).isEqualTo("Anderer Prompt");
        assertThat(service.current().temperature()).isEqualTo(1.1);
    }

    @Test
    void cacheIsOnlyUpdatedAfterCommit() {
        service.current();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.store(form(ChatProvider.OLLAMA, "llama3.2", 0.4));

            assertThat(service.current().model()).isEqualTo("qwen3:8b");

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(service.current().model()).isEqualTo("llama3.2");
    }

    @Test
    void cacheKeepsOldValuesAfterRollback() {
        service.current();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.store(form(ChatProvider.OLLAMA, "llama3.2", 0.4));
            // Rollback: afterCommit wird nie aufgerufen
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(service.current().model()).isEqualTo("qwen3:8b");
    }

    @Test
    void readingDoesNotOverwriteCache() {
        service.current();
        service.getSettings().setModel("nur-im-speicher");

        service.getSettings();

        assertThat(service.current().model()).isEqualTo("qwen3:8b");
    }

    @Test
    void modelListIsFetchedOutsideOfTransaction() {
        when(registry.models(ChatProvider.OLLAMA)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of("qwen3:8b");
        });

        service.update(form(ChatProvider.OLLAMA, "qwen3:8b", 0.7));
    }

    @Test
    void snapshotUpdateKeepsVoices() {
        service.update(form(ChatProvider.OLLAMA, "qwen3:8b", 0.7));

        service.update(new SettingsSnapshot(ChatProvider.OLLAMA, "llama3.2", "Neu", 1.0, false));

        assertThat(service.current().model()).isEqualTo("llama3.2");
        assertThat(service.getVoiceFor("de")).isEqualTo("de-voice");
    }

    @Test
    void resetRestoresDefaults() {
        service.update(form(ChatProvider.OLLAMA, "llama3.2", 1.2));

        service.resetToDefaults();

        assertThat(service.current()).isEqualTo(new SettingsSnapshot(ChatProvider.OLLAMA, "qwen3:8b",
                SettingsService.DEFAULT_SYSTEM_PROMPT, 0.7, false));
    }
}
