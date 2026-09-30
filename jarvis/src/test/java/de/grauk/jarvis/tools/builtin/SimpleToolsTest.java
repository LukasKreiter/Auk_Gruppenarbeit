package de.grauk.jarvis.tools.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import de.grauk.jarvis.knowledge.FactMemory;
import de.grauk.jarvis.status.MetricsService;
import de.grauk.jarvis.status.MetricsService.SystemSnapshot;

class SimpleToolsTest {

    @Test
    void dateTimeContainsWeekdayAndTime() {
        assertThat(new DateTimeTools().getCurrentDateTime()).containsPattern("\\d{2}\\.\\d{2}\\.\\d{4}, \\d{2}:\\d{2} Uhr");
    }

    @Test
    void systemStatusShowsUnknownGpu() {
        MetricsService metrics = mock(MetricsService.class);
        long gib = 1024L * 1024 * 1024;
        when(metrics.snapshot()).thenReturn(new SystemSnapshot(0.25, 8 * gib, 16 * gib, null, null));

        String result = new SystemTools(metrics).getSystemStatus();

        assertThat(result).contains("25 %").contains("8,0 von 16,0 GB").contains("GPU-Speicher: unbekannt");
    }

    @Test
    void memoryToolsDelegateAndFormat() {
        FactMemory memory = mock(FactMemory.class);
        when(memory.search(anyString(), anyInt()))
                .thenReturn(List.of(new Document("Lukas mag Tee", Map.of("type", "fact"))));
        MemoryTools tools = new MemoryTools(memory);

        assertThat(tools.rememberFact("Lukas mag Tee")).contains("Gemerkt");
        verify(memory).remember("Lukas mag Tee", null);
        assertThat(tools.searchKnowledge("Getränk")).isEqualTo("- [fact] Lukas mag Tee");
    }

    @Test
    void memoryToolsReportUnavailableEmbeddingService() {
        FactMemory memory = mock(FactMemory.class);
        when(memory.search(anyString(), anyInt())).thenThrow(new IllegalStateException("down"));

        assertThat(new MemoryTools(memory).searchKnowledge("x")).contains("nicht möglich").contains("down");
    }
}
