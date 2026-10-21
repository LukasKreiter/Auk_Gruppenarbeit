package de.grauk.jarvis.web;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stand-in data for the dashboard prototype. Every method here is a
 * placeholder for a real collaborator that already exists as an (empty)
 * stub in this project:
 *
 *   status()    -> OllamaHealthIndicator, VoiceHealthIndicator, DocumentIngestionService
 *   model()     -> ChatClientConfig / AssistantSettings
 *   system()    -> MetricsService
 *   tools()     -> ToolRegistry / ToolSetting
 *   logs()      -> LogBufferAppender
 *   ingestion() -> DocumentIngestionService
 *   detail()    -> Repositories (Conversation), KnowledgeDocument, SettingsService
 *
 * Swap each one out for the real service as it gets implemented; the
 * controllers and templates don't need to change shape when you do.
 */
final class DashboardMockData {

    private DashboardMockData() {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ---------------------------------------------------------------
    // left rail: connection + knowledge-base status
    // ---------------------------------------------------------------

    record StatusInfo(boolean ollamaOnline, boolean voiceOnline, int kbPercent, String uptime, String activeModel) {
    }

    static StatusInfo status() {
        return new StatusInfo(true, true, 41, "0T 12Std 32Min", "llama3.1:8b");
    }

    // ---------------------------------------------------------------
    // right rail: active model
    // ---------------------------------------------------------------

    record ModelInfo(String activeModel, double temperature) {
    }

    static ModelInfo model() {
        return new ModelInfo("llama3.1:8b", 0.70);
    }

    // ---------------------------------------------------------------
    // right rail: live system stats (jitter added so polling looks alive)
    // ---------------------------------------------------------------

    record SystemInfo(double tokensPerSecond, int latencyMs, int cpuPct, int memPct) {
    }

    static SystemInfo system() {
        var rnd = ThreadLocalRandom.current();
        return new SystemInfo(
                32 + rnd.nextDouble(0, 12),
                350 + rnd.nextInt(0, 150),
                18 + rnd.nextInt(0, 25),
                48 + rnd.nextInt(0, 10)
        );
    }

    // ---------------------------------------------------------------
    // right rail: tool registry toggles (in-memory only — back this with
    // the ToolSetting entity once persistence is wired up)
    // ---------------------------------------------------------------

    record ToolItem(String id, String label, boolean enabled) {
    }

    private static final Map<String, String> TOOL_LABELS = new LinkedHashMap<>();
    private static final Map<String, Boolean> TOOL_STATE = new ConcurrentHashMap<>();

    static {
        TOOL_LABELS.put("web-search", "Websuche");
        TOOL_LABELS.put("calendar", "Kalender");
        TOOL_LABELS.put("notes", "Notizen");
        TOOL_LABELS.put("calculator", "Rechner");
        TOOL_LABELS.put("weather", "Wetter");
        TOOL_LABELS.put("email", "E-Mail");

        TOOL_STATE.put("web-search", true);
        TOOL_STATE.put("calendar", true);
        TOOL_STATE.put("notes", false);
        TOOL_STATE.put("calculator", true);
        TOOL_STATE.put("weather", true);
        TOOL_STATE.put("email", false);
    }

    static List<ToolItem> tools() {
        return TOOL_LABELS.entrySet().stream()
                .map(e -> new ToolItem(e.getKey(), e.getValue(), TOOL_STATE.getOrDefault(e.getKey(), false)))
                .toList();
    }

    static void toggleTool(String id) {
        TOOL_STATE.computeIfPresent(id, (k, v) -> !v);
    }

    // ---------------------------------------------------------------
    // right rail: rotating sample of "live" log lines
    // ---------------------------------------------------------------

    record LogLine(String time, String level, String message) {
    }

    private static final List<String> LOG_POOL = List.of(
            "INFO|Chat-Anfrage verarbeitet (312 ms)",
            "INFO|Dokument \"handbuch.pdf\" indiziert",
            "WARN|Sprachdienst Antwort verzögert",
            "INFO|Ollama Health-Check OK",
            "INFO|Neue Unterhaltung gestartet",
            "INFO|Einstellungen gespeichert",
            "WARN|Vektorspeicher-Antwort > 800 ms",
            "INFO|Werkzeug \"Websuche\" aufgerufen"
    );

    static List<LogLine> logs() {
        int window = 5;
        int offset = (int) ((System.currentTimeMillis() / 4000) % LOG_POOL.size());
        var now = LocalTime.now();
        return java.util.stream.IntStream.range(0, window)
                .mapToObj(i -> {
                    String entry = LOG_POOL.get((offset + i) % LOG_POOL.size());
                    String[] parts = entry.split("\\|", 2);
                    String time = now.minusSeconds((long) (window - i) * 14).format(TIME);
                    return new LogLine(time, parts[0], parts[1]);
                })
                .toList();
    }

    // ---------------------------------------------------------------
    // right rail: knowledge-base ingestion progress
    // ---------------------------------------------------------------

    record IngestionInfo(int documentsPct, int embeddingsPct, String currentFile) {
    }

    private static final List<String> INGEST_FILES = List.of(
            "handbuch_v2.pdf", "changelog.md", "faq.docx", "architektur.pdf"
    );

    static IngestionInfo ingestion() {
        long t = System.currentTimeMillis();
        int documents = (int) ((t / 150) % 100);
        int embeddings = (int) ((t / 230) % 100);
        String file = INGEST_FILES.get((int) ((t / 6000) % INGEST_FILES.size()));
        return new IngestionInfo(documents, embeddings, file);
    }

    // ---------------------------------------------------------------
    // center: detail panel behind the nav chips
    // ---------------------------------------------------------------

    record DetailInfo(String title, List<String> items) {
    }

    static DetailInfo detail(String key) {
        return switch (key) {
            case "knowledge" -> new DetailInfo("Wissensbasis", List.of(
                    "handbuch_v2.pdf — 48 Abschnitte",
                    "changelog.md — 12 Abschnitte",
                    "faq.docx — 9 Abschnitte"
            ));
            case "logs" -> new DetailInfo("Systemereignisse", List.of(
                    "Ollama neu gestartet — vor 3 Std",
                    "Sprachdienst-Timeout behoben — vor 5 Std",
                    "Wissensbasis aktualisiert — vor 1 Tag"
            ));
            case "settings" -> new DetailInfo("Einstellungen", List.of(
                    "Modell: llama3.1:8b",
                    "Temperatur: 0.70",
                    "Sprachausgabe: aktiviert"
            ));
            default -> new DetailInfo("Letzte Unterhaltungen", List.of(
                    "Wetter für morgen früh abgefragt — vor 12 Min",
                    "Zusammenfassung des PDF „Q3-Bericht” erstellt — vor 1 Std",
                    "Erinnerung „Serverwartung” angelegt — gestern"
            ));
        };
    }

    // ---------------------------------------------------------------
    // center: canned chat reply until AssistantService is implemented
    // ---------------------------------------------------------------

    static String reply(String userMessage) {
        return "Ich habe „" + userMessage + "“ erhalten. Sobald AssistantService angebunden ist, "
                + "antworte ich hier mit dem echten Modell.";
    }
}
