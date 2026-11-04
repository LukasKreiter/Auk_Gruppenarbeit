# Code-Dokumentation – Backend KI-Kern

Verantwortlich: **Brenner**. Umfasst die Pakete `assistant/`, `knowledge/` und `tools/` unter
`jarvis/src/main/java/de/grauk/jarvis/`. Fachbegriffe siehe [`CONTEXT.md`](../CONTEXT.md),
Entscheidungen siehe [`adr/`](adr/).

> Stand: umgesetzt (Phasen 0, 1, 1b, 5, 6 aus dem README), geprüft gegen Spring Boot 4.1.1 /
> Spring AI 2.0.1. `./mvnw verify` läuft ohne LLM-Server und ohne Claude-CLI
> (Modelle und CLI in Tests gefakt). Noch nicht gegen einen echten Ollama-Server getestet.

## Überblick

```
web/ChatController (Hiebler)
   │  stream(conversationId, text)          ▲ Flux<AssistantEvent>  → SSE
   ▼                                        │
assistant/AssistantService ─────────────────┘
   ├── ChatProviderRegistry ──► ChatClient (Ollama)  → LLM-Server :11434
   │                        └─► ChatClient (Claude)  → api.anthropic.com
   ├── SettingsService (Hiebler)            System-Prompt, Provider, Modell, Temperatur, Thinking
   ├── MessageChatMemoryAdvisor             Chat-Verlauf (JDBC, Tabelle SPRING_AI_CHAT_MEMORY)
   ├── ToolCallingAdvisor (Spring AI)       führt Tool-Aufrufe aus, Schleife pro Tool-Runde
   ├── GuardedRagAdvisor ──► QuestionAnswerAdvisor ──► VectorStore (knowledge/)
   └── ToolRegistry (tools/) ──► aktive ToolCallbacks (ListenerToolCallback, ggf. ConfirmationGate)
```

## Paket `assistant/`

Zweck: eine Anfrage entgegennehmen, den richtigen Provider wählen, Prompt + Advisors + Tools
zusammenbauen und die Antwort als Event-Stream liefern.

| Klasse | Verantwortung |
|---|---|
| `AssistantService` | Einstiegspunkt der Chat-Pipeline. Einzige Klasse, die `web/` aufruft. |
| `AssistantEvent` | `sealed interface` für alle Stream-Ereignisse (Vertrag zu `web/`), inkl. `sseName()`. |
| `ToolCallRecord` | Protokoll eines Tool-Aufrufs im `Done`-Event. |
| `ChatProvider` | `enum { OLLAMA, ANTHROPIC }`. |
| `ChatProviderRegistry` | Hält je Provider einen `ChatClient`, kennt Verfügbarkeit und Modelllisten. |
| `ChatClients` | Record der beiden `ChatClient`s (kein eigenes Bean pro Client, damit nichts mehrdeutig wird). |
| `ChatClientConfig` | `@Configuration`: baut die `ChatClient`s, `ChatMemory` und die Advisors explizit. |
| `GuardedRagAdvisor` | Umschließt den `QuestionAnswerAdvisor`: Suche schlägt fehl → Anfrage läuft ohne RAG weiter + `Warning`. |
| `PromptTemplates` | Default-System-Prompt, RAG-Template (deutsch, Kontext = Daten, keine Anweisungen). |
| `AssistantProperties` | `jarvis.assistant.*` (Default-Provider, Claude-Modellliste, Memory-Fenster). |
| `ProviderUnavailableException` | Gewählter Provider nicht erreichbar oder nicht konfiguriert (ADR 0005). |

### `AssistantService`

```java
public Flux<AssistantEvent> stream(long conversationId, String userText);
```

Ablauf pro Anfrage:

1. `SettingsService.current()` lesen → Provider, Modell, System-Prompt, Temperatur, Thinking.
2. `ChatProviderRegistry.clientFor(provider)` → `ChatClient`, sonst `ProviderUnavailableException` → `Error`.
3. Provider-spezifische Optionen (`OllamaChatOptions` bzw. `ClaudeCliChatOptions`) als **Builder** an
   `.options(...)` – in Spring AI 2.0.1 nimmt `ChatClientRequestSpec.options` einen `ChatOptions.Builder`.
   - Ollama: `model`, `temperature`, `enableThinking()` / `disableThinking()`.
   - Claude: `model`, `thinking(...)` → `--effort high` bzw. `low`; die CLI kennt keine Temperatur.
4. Prompt: System-Prompt + `MessageChatMemoryAdvisor` (Konversations-ID = `conversationId` als String)
   + `GuardedRagAdvisor` (Schwellwert/Top-K aus `jarvis.knowledge.*`) + `ToolRegistry.enabledCallbacks()`.
   Pro Anfrage kommt ein `ToolInvocationListener` in den ToolContext; er schiebt `ToolCalled` /
   `ConfirmationRequired` in den Stream und sammelt die `ToolCallRecord`s.
5. `.stream().chatResponse()` auf `boundedElastic` → Text-Chunks als `Token` (Thinking-Inhalte nicht).
6. Ende: genau ein `Done` (Text, Provider, Modell, `llmMillis`, Tool-Aufrufe) **oder** ein `Error`.
   Der Flux endet immer normal (nie `onError`), damit `web/` den Fehler als SSE-Event senden kann.
   Leere Antwort → `Error`, nicht `Done`.

Fehlertexte (`Error.userMessage`):

| Ursache | Text |
|---|---|
| Ollama nicht erreichbar | „LLM-Server nicht erreichbar – in den Einstellungen kann Claude gewählt werden“ |
| Claude-CLI fehlt, nicht angemeldet, Limit erreicht, Absturz | Text der `ProviderUnavailableException` |
| Zeitüberschreitung | „Zeitüberschreitung – … antwortet nicht.“ |
| HTTP 404, 429 | Modell nicht gefunden / Rate-Limit erreicht |

Persistenz der Nachricht und der Tool-Aufrufe macht **nicht** der `AssistantService`, sondern
`conversation/` (Hiebler) anhand von `Done`. Die Advisor-Reihenfolge ist Memory → ToolCalling → RAG:
Der Chat-Verlauf speichert daher nur Nutzertext und finale Antwort, nicht den RAG-Kontext oder
Tool-Nachrichten. Schlägt die Anfrage fehl, bleibt die Nutzernachricht im Chat-Verlauf stehen
(Spring-AI-Verhalten, unkritisch).

### `AssistantEvent`

```java
public sealed interface AssistantEvent {
    String sseName();                                                   // token, tool, confirm, warning, done, error
    record Token(String text) implements AssistantEvent {}
    record ToolCalled(String toolName, String argumentsJson) implements AssistantEvent {}
    record ConfirmationRequired(UUID requestId, String toolName, String argumentsJson) implements AssistantEvent {}
    record Warning(String message) implements AssistantEvent {}          // z. B. RAG nicht verfügbar
    record Done(String fullText, ChatProvider provider, String model,
                long llmMillis, List<ToolCallRecord> toolCalls) implements AssistantEvent {}
    record Error(String userMessage) implements AssistantEvent {}
}
public record ToolCallRecord(String toolName, String argumentsJson, String result) {}
```

### `ChatProviderRegistry`

```java
public ChatClient clientFor(ChatProvider provider);   // wirft ProviderUnavailableException
public Map<ChatProvider, Boolean> available();        // Ollama: /api/tags erreichbar; Claude: CLI angemeldet
public ChatProvider active();                          // aus Settings
public List<String> models(ChatProvider provider);    // Ollama live aus /api/tags, Claude aus Config
```

- Das Ollama-Probe (`OllamaApi.listModels()`) hat 2 s Timeout und wird 4 s gecacht – die Status-Seite pollt.
  Fehler → `false` bzw. leere Liste, Ursache im Log.
- Der `ClaudeCliChatModel`-Bean existiert auch ohne CLI; `clientFor(ANTHROPIC)` prüft die Anmeldung selbst.
  „Verfügbar“ heißt bei Claude nur „`claude auth status` endet mit Exit 0“ (30 s gecacht), nicht „erreichbar“.
- `clientFor(OLLAMA)` prüft die Erreichbarkeit nicht vorab; ein toter Server zeigt sich in der Anfrage als `Error`.

### `ClaudeCliChatModel` (ADR 0006)

Pro Anfrage ein `claude -p`-Prozess in einem leeren Temp-Verzeichnis. Der Verlauf aus den Advisors geht als Text an
stdin, der System-Prompt als Datei. Der Lauf endet erst nach dem Aufräumen: Prozess beendet, Token ungültig,
Verzeichnis gelöscht. Abbruch (Client weg) und `idle-timeout` beenden den Prozess ebenfalls.

- `ClaudeStreamParser` liest `stream-json`. Antworttext kommt nur aus `text_delta`, Fehler aus dem `result`-Ereignis
  und dem `error`-Feld der synthetischen `assistant`-Nachricht.
- `ClaudeToolBridge` hält Tools und `ToolContext` je Lauf unter einem Zufalls-Token. `web/ClaudeMcpController`
  (`POST /internal/claude-mcp`) beantwortet damit die MCP-Aufrufe der CLI; die Tools laufen wie bei Ollama durch
  `ListenerToolCallback` und `ConfirmationGate`.
- Leere Argumente immer als `--x=` schreiben: Java verwirft `""` unter Windows stillschweigend.

Wird von `settings/` (Dropdown) und `status/` (Ampel) nur gelesen.

## Paket `knowledge/`

Zweck: Dokumente und Fakten einbetten, im VectorStore halten und für RAG bereitstellen.

| Klasse | Verantwortung |
|---|---|
| `VectorStoreConfig` | `SimpleVectorStore`-Bean mit `bge-m3`; lädt JSON-Datei beim Start, `persist()` speichert atomar (Temp-Datei + Move). |
| `DocumentIngestionService` | Upload → Tika-Reader → `TokenTextSplitter` → `VectorStore.add` (Metadaten `type=document`, `documentId`, `filename`). |
| `KnowledgeDocument` | JPA-Entity `knowledge_document` (Dateiname, Chunk-Anzahl, Status, Statusmeldung, `indexed_at`). |
| `FactMemory` | Fakten merken, auflisten, löschen – immer DB **und** VectorStore gemeinsam; Suche für `searchKnowledge`. |
| `MemoryFact` | JPA-Entity `memory_fact`. |
| `KnowledgeProperties` | `jarvis.knowledge.*` (Datei, Schwellwert, Top-K). |
| `KnowledgeException` | Fehler mit nutzerlesbarer Meldung (z. B. Embedding fehlgeschlagen). |
| `KnowledgeStartupCheck` | Beim Start: `PENDING` → `FAILED`, Warnung bei indexierten Einträgen ohne VectorStore-Datei. |
| Repositories | `KnowledgeDocumentRepository`, `MemoryFactRepository`. |

```java
// DocumentIngestionService
public KnowledgeDocument ingest(MultipartFile file);   // Status PENDING → INDEXED | FAILED (+ statusMessage), wirft nicht
public void delete(long documentId);                   // entfernt alle Chunks mit dieser documentId + Zeile
public List<KnowledgeDocument> list();

// FactMemory
public MemoryFact remember(String content, @Nullable Long sourceMessageId);  // IllegalArgumentException | KnowledgeException
public List<MemoryFact> list();
public void delete(long factId);
public List<Document> search(String query, int topK);  // Dokumente UND Fakten, mit Schwellwert
```

Regeln:

- Embeddings kommen immer von `bge-m3` (ADR 0003). Ist der LLM-Server weg, schlägt Ingestion mit
  Status `FAILED` + Grund fehl; im Chat kommt `AssistantEvent.Warning`, keine stille Degradation.
- Nach jeder Änderung am VectorStore `VectorStoreConfig.persist()` aufrufen.
- DB-Eintrag und VectorStore-Eintrag gehören zusammen: beim Löschen beide entfernen, beim Fehlschlag
  des Einbettens keinen verwaisten Eintrag zurücklassen (Chunks werden wieder entfernt, Fakt-Zeile gelöscht).
- Ids in den Metadaten sind **Strings** (`documentId="5"`, `factId="7"`), weil `SimpleVectorStore` über
  JSON serialisiert und Zahlen nach dem Laden sonst nicht mehr zum Filter passen. Fakt-Vektoren haben
  die Dokument-Id `fact-<id>`.
- Upload: nur `pdf`, `docx`, `md`, `txt`; extrahierter Text und Chunk-Anzahl sind begrenzt (Schutz vor
  Dekompressionsbomben und endlosen Embedding-Läufen).
- Beim Start: `PENDING`-Dokumente (Absturz während der Indexierung) werden `FAILED`; gibt es indexierte
  Dokumente/Fakten, aber einen leeren VectorStore, wird das als ERROR geloggt.
- Eine beschädigte `vector-store.json` bricht den Start ab (statt sie beim nächsten `persist()` zu überschreiben).

## Paket `tools/`

Zweck: Tools bereitstellen, nach Einstellungen filtern und Bestätigungspflicht durchsetzen.

| Klasse | Verantwortung |
|---|---|
| `ToolRegistry` | Findet alle Beans mit `@Tool`-Methoden (Paket `de.grauk.jarvis`), gleicht sie mit `tool_setting` ab (fehlende Zeilen anlegen, bestehende nie überschreiben), liefert aktive `ToolCallback`s. Einstellungen werden pro Anfrage frisch gelesen. |
| `ToolSetting` / `ToolSettingRepository` | JPA-Entity `tool_setting` (`tool_name`, `enabled`, `requires_confirmation`). |
| `RequiresConfirmation` | Annotation an `@Tool`-Methoden, die etwas auf dem PC ausführen. Ist eine **Untergrenze**: lässt sich per Einstellung nicht abschalten. |
| `ToolInvocationListener` | Rückkanal Tool → laufende Anfrage (liegt im ToolContext unter `CONTEXT_KEY`). |
| `ListenerToolCallback` | Wrapper je Tool: meldet Aufruf/Ergebnis an den Listener, schaltet ggf. das `ConfirmationGate` vor; Tool-Exceptions werden geloggt und als Ergebnistext ans Modell gegeben. |
| `ConfirmationGate` | Sendet `ConfirmationRequired`, wartet auf Freigabe (Timeout `jarvis.tools.confirmation-timeout`). Abgelehnt, Timeout oder kein Listener → Tool läuft **nicht**. |
| `ConfirmationNotFoundException` | `resolve` mit unbekannter/abgelaufener Id → in `web/` als 404 abbilden. |
| `TimerExpiredEvent` | Spring-Event bei Timer-Ablauf (`timerId`, `label`, `minutes`, `expiredAt`). |
| `ToolsProperties` / `ToolsConfig` | `jarvis.tools.*`; eigener `toolTaskScheduler` (Boot legt ohne `@EnableScheduling` keinen an). |
| `builtin/DateTimeTools` | `getCurrentDateTime()` |
| `builtin/TimerTools` | `setTimer(minutes, label)` (1–1440 min) über `toolTaskScheduler`; bei Ablauf `TimerExpiredEvent`. Timer liegen nur im Speicher. |
| `builtin/WeatherTools` | `getWeather(city)` über Open-Meteo (Geocoding → Forecast, `RestClient` mit Timeouts). Einziges Tool mit Internet. |
| `builtin/MemoryTools` | `rememberFact(text)`, `searchKnowledge(query)` → delegiert an `knowledge/`. |
| `builtin/ApplicationTools` | `openApplication(name)` – nur Einträge der Allowlist, `ProcessBuilder` mit dem konfigurierten Pfad, keine Shell, keine Argumente vom Modell; `@RequiresConfirmation`. |
| `builtin/SystemTools` | `getSystemStatus()` → `MetricsService` (Hiebler). |

```java
// ToolRegistry
public List<ToolCallback> enabledCallbacks();
public List<ToolInfo> all();                         // Name, Beschreibung, enabled, requiresConfirmation (effektiv)
public void update(String toolName, boolean enabled, boolean requiresConfirmation);  // IllegalArgumentException

// ConfirmationGate
public String execute(String toolName, String argumentsJson, ToolInvocationListener listener, Supplier<String> action);
public void resolve(UUID requestId, boolean approved);   // aufgerufen von web/ (POST /api/tools/confirmations/{id})
```

Neues Tool hinzufügen: Klasse in `tools/builtin/` mit `@Component` und `@Tool`-Methoden
(aussagekräftige `description`, Parameter mit `@ToolParam`). `ToolRegistry` findet es automatisch, die
`tool_setting`-Zeile wird beim Start angelegt (aktiv, keine Bestätigung – außer mit `@RequiresConfirmation`).
Tool-Namen müssen eindeutig sein, sonst bricht der Start ab.

## Konfiguration (`application.yml`)

```yaml
spring:
  ai:
    ollama:
      base-url: ${JARVIS_LLM_URL:http://llm-server:11434}
      init.pull-model-strategy: never
      chat.options: { model: qwen3:8b, keep-alive: 30m }
      embedding.options.model: bge-m3
    chat:
      client.enabled: false                 # ChatClients baut ChatClientConfig selbst (ADR 0002)
      memory.repository.jdbc.initialize-schema: never   # Schema kommt aus Flyway V1
    model:
      embedding: ollama
jarvis:
  assistant:
    default-provider: OLLAMA
    anthropic-models: [claude-sonnet-5-5, claude-haiku-4-5-20251001]
    memory-window: 20
  knowledge:
    vector-store-file: ./data/vector-store.json
    similarity-threshold: 0.6
    top-k: 4
  tools:
    confirmation-timeout: 60s
    weather: { geocoding-url: …, forecast-url: …, timeout: 5s }
    open-application:
      allowlist:
        notepad: C:\Windows\System32\notepad.exe
        rechner: C:\Windows\System32\calc.exe
```

- Die H2-Konsole ist standardmäßig aus (Schutz vor DNS-Rebinding auf `127.0.0.1`); lokal zum Debuggen
  mit `SPRING_H2_CONSOLE_ENABLED=true` einschalten.
- Tests: `src/test/resources/config/application.yml` überlagert die Haupt-Config (H2 in-memory,
  VectorStore-Datei im Temp-Verzeichnis, nicht vorhandene Claude-CLI) statt sie zu ersetzen.

## Spring AI 2.0.1 – Abweichungen von 1.x-Beispielen

- `ChatClientRequestSpec.options(...)` und `ChatClient.Builder.defaultOptions(...)` nehmen einen `ChatOptions.Builder`.
- Tool-Ausführung läuft über den `ToolCallingAdvisor` (vom Default-`ChatClient` automatisch hinzugefügt,
  Tool-Ausführung auf `boundedElastic`); `toolContext` kommt beim Streaming in `ToolCallback.call(String, ToolContext)` an.
- `ToolCallbacks` liegt in `org.springframework.ai.support`, nicht in `org.springframework.ai.tool`.
- `@DataJpaTest` liegt in `org.springframework.boot.data.jpa.test.autoconfigure`; Jackson 3 (`tools.jackson`).

## Schnittstellen zu anderen Teammitgliedern

| Wer | Richtung | Vertrag |
|---|---|---|
| Hiebler – `web/ChatController` | ruft auf | `AssistantService.stream(...)` → SSE mit `event.sseName()` als Eventname |
| Hiebler – `conversation/` | konsumiert | `AssistantEvent.Done` → speichert `message` (+ `provider`, `model`, `llm_ms`) und `tool_call` |
| Hiebler – `settings/` | liefert / liest | liefert `SettingsService.current()` → `SettingsSnapshot`; liest `ChatProviderRegistry.models()/available()` |
| Hiebler – `status/` | liest / liefert | liest `ChatProviderRegistry.available()`; liefert `MetricsService.snapshot()` für `SystemTools` |
| Hiebler – `web/` | ruft auf | `ConfirmationGate.resolve(...)` (`ConfirmationNotFoundException` → 404), `ToolRegistry.all()/update(...)`, `DocumentIngestionService`, `FactMemory` |
| Hiebler – `web/` | konsumiert | `TimerExpiredEvent` (`@EventListener`) → SSE ans Dashboard + TTS-Ansage |
| Zheng, Zugaj – Frontend | konsumiert | SSE-Events `token`/`tool`/`confirm`/`warning`/`done`/`error`, Timer-Event, „☁ Cloud“-Badge |
| Kreiter – LLM-Server | stellt bereit | Ollama unter `JARVIS_LLM_URL`, Firewall nur für Jarvis-PC (ADR 0001) |
| Steinwidder, Radaelli – voice-service | unabhängig | KI-Kern ruft den voice-service nicht auf; das macht `voice/` |

### Offene Punkte für Hiebler

- `settings/SettingsService` und `status/MetricsService` sind **Platzhalter** aus dem KI-Kern (in-memory Settings,
  JMX-Metriken ohne GPU). Die Signaturen `current()` → `SettingsSnapshot` und `snapshot()` → `SystemSnapshot`
  bitte beibehalten oder gemeinsam ändern.
- Flyway: `V1__init.sql` enthält die Tabellen des KI-Kerns und `SPRING_AI_CHAT_MEMORY`. `conversation`, `message`,
  `tool_call`, `assistant_settings` kommen als `V2__…`; Versionsnummern absprechen, damit es keine Kollision gibt.
- Zustandsändernde Endpoints (Bestätigung, Settings, Upload) sollten `Origin`/`Host` auf `localhost` prüfen,
  weil das Dashboard keine Authentifizierung hat.
- Noch kein Listener für `TimerExpiredEvent` – bis dahin läuft ein Timer ab, ohne dass jemand es bemerkt.
