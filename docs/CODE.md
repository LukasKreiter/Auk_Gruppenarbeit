# Code-Dokumentation – Backend KI-Kern

Verantwortlich: **Brenner**. Umfasst die Pakete `assistant/`, `knowledge/` und `tools/` unter
`jarvis/src/main/java/de/grauk/jarvis/`. Fachbegriffe siehe [`CONTEXT.md`](../CONTEXT.md),
Entscheidungen siehe [`adr/`](adr/).

> Stand: Entwurf vor der Umsetzung. Die Klassen existieren bisher nur als leere Stubs.
> Signaturen sind der vereinbarte Vertrag; beim Implementieren gegen Spring Boot 4.1.1 /
> Spring AI 2.0.1 prüfen und dieses Dokument mitziehen.

## Überblick

```
web/ChatController (Hiebler)
   │  stream(conversationId, text)          ▲ Flux<AssistantEvent>  → SSE
   ▼                                        │
assistant/AssistantService ─────────────────┘
   ├── ChatProviderRegistry ──► ChatClient (Ollama)  → LLM-Server :11434
   │                        └─► ChatClient (Claude)  → api.anthropic.com
   ├── SettingsService (Hiebler)            System-Prompt, Provider, Modell, Temperatur, Thinking
   ├── MessageChatMemoryAdvisor             Chat-Verlauf (JDBC)
   ├── QuestionAnswerAdvisor ──► VectorStore (knowledge/)   Dokument-Chunks + Fakten
   └── ToolRegistry (tools/) ──► aktivierte ToolCallbacks, ggf. über ConfirmationGate
```

## Paket `assistant/`

Zweck: eine Anfrage entgegennehmen, den richtigen Provider wählen, Prompt + Advisors + Tools
zusammenbauen und die Antwort als Event-Stream liefern.

| Klasse | Verantwortung |
|---|---|
| `AssistantService` | Einstiegspunkt der Chat-Pipeline. Einzige Klasse, die `web/` aufruft. |
| `AssistantEvent` | `sealed interface` für alle Stream-Ereignisse (Vertrag zu `web/`). |
| `ChatProvider` | `enum { OLLAMA, ANTHROPIC }`. |
| `ChatProviderRegistry` | Hält je Provider einen `ChatClient`, kennt Verfügbarkeit und Modelllisten. |
| `ChatClientConfig` | `@Configuration`: baut die `ChatClient`s, `ChatMemory` und die Advisors explizit. |
| `PromptTemplates` | Default-System-Prompt, RAG-Template (deutsch), Formulierung für Fakten im Kontext. |
| `ProviderUnavailableException` | Gewählter Provider nicht erreichbar oder nicht konfiguriert (ADR 0005). |

### `AssistantService`

```java
public Flux<AssistantEvent> stream(long conversationId, String userText);
```

Ablauf pro Anfrage:

1. `SettingsService.current()` lesen → Provider, Modell, System-Prompt, Temperatur, Thinking.
2. `ChatProviderRegistry.clientFor(provider)` → `ChatClient`, sonst `ProviderUnavailableException`.
3. Provider-spezifische Optionen bauen (`OllamaChatOptions` bzw. `AnthropicChatOptions`).
4. Prompt: System-Prompt + `MessageChatMemoryAdvisor` (Konversations-ID = `conversationId`)
   + `QuestionAnswerAdvisor` (Schwellwert/Top-K aus `jarvis.knowledge.*`) + `ToolRegistry.enabledCallbacks()`.
5. `.stream().chatResponse()` → auf `AssistantEvent.Token` mappen; Tool-Aufrufe als `ToolCalled`.
6. Am Ende `Done` mit Metadaten; Fehler als `Error` (nie still verschlucken).

Persistenz der Nachricht und der Tool-Aufrufe macht **nicht** der `AssistantService`, sondern
`conversation/` (Hiebler) anhand von `Done`. So bleibt der KI-Kern frei von JPA-Details der Konversation.

### `AssistantEvent`

```java
public sealed interface AssistantEvent {
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

SSE-Eventnamen (Vertrag mit Frontend): `token`, `tool`, `confirm`, `warning`, `done`, `error`.

### `ChatProviderRegistry`

```java
public ChatClient clientFor(ChatProvider provider);   // wirft ProviderUnavailableException
public Map<ChatProvider, Boolean> available();        // Ollama: /api/tags erreichbar; Claude: API-Key gesetzt
public ChatProvider active();                          // aus Settings
public List<String> models(ChatProvider provider);    // Ollama live aus /api/tags, Claude aus Config
```

Wird von `settings/` (Dropdown) und `status/` (Ampel) nur gelesen.

## Paket `knowledge/`

Zweck: Dokumente und Fakten einbetten, im VectorStore halten und für RAG bereitstellen.

| Klasse | Verantwortung |
|---|---|
| `VectorStoreConfig` | `SimpleVectorStore`-Bean mit `bge-m3`; lädt JSON-Datei beim Start, `persist()` speichert. |
| `DocumentIngestionService` | Upload → Tika-Reader → `TokenTextSplitter` → `VectorStore.add` (Metadaten `type=document`, `documentId`). |
| `KnowledgeDocument` | JPA-Entity `knowledge_document` (Dateiname, Chunk-Anzahl, Status, `indexed_at`). |
| `FactMemory` | Fakten merken, auflisten, löschen – immer DB **und** VectorStore gemeinsam. |
| `MemoryFact` | JPA-Entity `memory_fact` (neu anzulegen). |
| Repositories | `KnowledgeDocumentRepository`, `MemoryFactRepository`. |

```java
// DocumentIngestionService
public KnowledgeDocument ingest(MultipartFile file);   // Status PENDING → INDEXED | FAILED
public void delete(long documentId);                   // entfernt alle Chunks mit dieser documentId
public List<KnowledgeDocument> list();

// FactMemory
public MemoryFact remember(String content, @Nullable Long sourceMessageId);
public List<MemoryFact> list();
public void delete(long factId);
public List<Document> search(String query, int topK);  // für das Tool searchKnowledge
```

Regeln:

- Embeddings kommen immer von `bge-m3` (ADR 0003). Ist der LLM-Server weg, schlägt Ingestion mit
  Status `FAILED` + Grund fehl; im Chat kommt `AssistantEvent.Warning`, keine stille Degradation.
- Nach jeder Änderung am VectorStore `VectorStoreConfig.persist()` aufrufen.
- DB-Eintrag und VectorStore-Eintrag gehören zusammen: beim Löschen beide entfernen, beim Fehlschlag
  des Einbettens keinen verwaisten DB-Eintrag zurücklassen.

## Paket `tools/`

Zweck: Tools bereitstellen, nach Einstellungen filtern und Bestätigungspflicht durchsetzen.

| Klasse | Verantwortung |
|---|---|
| `ToolRegistry` | Sammelt alle Tool-Beans, gleicht sie mit `tool_setting` ab (fehlende Zeilen anlegen), liefert aktive `ToolCallback`s. |
| `ToolSetting` | JPA-Entity `tool_setting` (`tool_name`, `enabled`, `requires_confirmation`). |
| `ConfirmationGate` | Wrappt `ToolCallback`s mit Bestätigungspflicht: sendet `ConfirmationRequired`, wartet auf Freigabe (Timeout `jarvis.tools.confirmation-timeout`). |
| `builtin/DateTimeTools` | `getCurrentDateTime()` |
| `builtin/TimerTools` | `setTimer(minutes, label)` über `TaskScheduler`; bei Ablauf `TimerExpiredEvent` (Spring `ApplicationEvent`). |
| `builtin/WeatherTools` | `getWeather(city)` über Open-Meteo (`RestClient`, Timeout). |
| `builtin/MemoryTools` | `rememberFact(text)`, `searchKnowledge(query)` → delegiert an `knowledge/`. |
| `builtin/ApplicationTools` | `openApplication(name)` – nur Einträge aus der Allowlist, Default „Bestätigung nötig“. |
| `builtin/SystemTools` | `getSystemStatus()` → `MetricsService` (Hiebler). |

```java
// ToolRegistry
public List<ToolCallback> enabledCallbacks();
public List<ToolInfo> all();                         // für die Tools-Seite: Name, Beschreibung, Flags
public void update(String toolName, boolean enabled, boolean requiresConfirmation);

// ConfirmationGate
public void resolve(UUID requestId, boolean approved);   // aufgerufen von web/ (POST /api/tools/confirmations/{id})
```

Neues Tool hinzufügen: Klasse in `tools/builtin/` mit `@Component` und `@Tool`-Methoden
(aussagekräftige `description`, Parameter mit `@ToolParam`). `ToolRegistry` findet es automatisch, die
`tool_setting`-Zeile wird beim Start angelegt (standardmäßig aktiv, keine Bestätigung – außer Tools, die
etwas auf dem PC ausführen).

## Konfiguration (`application.yml`)

```yaml
spring:
  ai:
    ollama:
      base-url: ${JARVIS_LLM_URL:http://llm-server:11434}
      chat.options.model: qwen3:8b
      embedding.options.model: bge-m3
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}        # nur aus Umgebungsvariable
jarvis:
  assistant:
    default-provider: OLLAMA
    anthropic-models: [claude-sonnet-5-5, claude-haiku-4-5-20251001]
  voice:
    base-url: ${JARVIS_VOICE_URL:http://llm-server:8090}
    token: ${JARVIS_VOICE_TOKEN:}
  knowledge:
    vector-store-file: ./data/vector-store.json
    similarity-threshold: 0.6
    top-k: 4
  tools:
    confirmation-timeout: 60s
    open-application:
      allowlist:
        notepad: C:\Windows\System32\notepad.exe
```

Property-Namen unter `spring.ai.*` beim Umsetzen gegen Spring AI 2.0.1 prüfen.

## Schnittstellen zu anderen Teammitgliedern

| Wer | Richtung | Vertrag |
|---|---|---|
| Hiebler – `web/ChatController` | ruft auf | `AssistantService.stream(...)` → SSE mit den Eventnamen oben |
| Hiebler – `conversation/` | konsumiert | `AssistantEvent.Done` → speichert `message` (+ `provider`, `model`, `llm_ms`) und `tool_call` |
| Hiebler – `settings/` | liefert / liest | liefert `SettingsService.current()`; liest `ChatProviderRegistry.models()/available()` |
| Hiebler – `status/` | liest | `ChatProviderRegistry.available()`; `MetricsService` wird von `SystemTools` genutzt |
| Hiebler – `web/` | ruft auf | `ConfirmationGate.resolve(...)`, `ToolRegistry.all()/update(...)`, `DocumentIngestionService`, `FactMemory` |
| Zheng, Zugaj – Frontend | konsumiert | SSE-Events `token`/`tool`/`confirm`/`warning`/`done`/`error`, Timer-Event, „☁ Cloud“-Badge |
| Kreiter – LLM-Server | stellt bereit | Ollama unter `JARVIS_LLM_URL`, Firewall nur für Jarvis-PC (ADR 0001) |
| Steinwidder, Radaelli – voice-service | unabhängig | KI-Kern ruft den voice-service nicht auf; das macht `voice/` |
