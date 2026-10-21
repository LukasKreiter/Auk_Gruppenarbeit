package de.grauk.jarvis.tools.builtin;

import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import de.grauk.jarvis.status.MetricsService;
import de.grauk.jarvis.status.MetricsService.SystemSnapshot;

@Component
public class SystemTools {

    private static final double GIB = 1024.0 * 1024 * 1024;

    private final MetricsService metricsService;

    public SystemTools(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @Tool(description = "Liefert den aktuellen Systemstatus des PCs: CPU-Auslastung, Arbeitsspeicher und "
            + "GPU-Speicher. Verwenden bei Fragen wie 'Wie ausgelastet ist mein Rechner?'.")
    public String getSystemStatus() {
        SystemSnapshot s = metricsService.snapshot();
        String cpu = s.cpuLoad() < 0 || Double.isNaN(s.cpuLoad()) ? "unbekannt" : String.format(Locale.GERMAN, "%.0f %%", s.cpuLoad() * 100);
        String ram = String.format(Locale.GERMAN, "%.1f von %.1f GB belegt", s.usedMemoryBytes() / GIB,
                s.totalMemoryBytes() / GIB);
        String gpu = s.gpuMemoryUsedBytes() == null || s.gpuMemoryTotalBytes() == null ? "unbekannt"
                : String.format(Locale.GERMAN, "%.1f von %.1f GB belegt", s.gpuMemoryUsedBytes() / GIB,
                        s.gpuMemoryTotalBytes() / GIB);
        return "CPU-Auslastung: " + cpu + "; Arbeitsspeicher: " + ram + "; GPU-Speicher: " + gpu + ".";
    }
}
