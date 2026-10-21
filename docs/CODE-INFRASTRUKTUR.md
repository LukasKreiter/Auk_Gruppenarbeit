# Code-Dokumentation – Backend Infrastruktur

Verantwortlich: **Hiebler**. Umfasst die Pakete `conversation/`, `settings/` und `web/` unter
`jarvis/src/main/java/de/grauk/jarvis/` sowie `status/`. Der KI-Kern steht in
[`CODE.md`](CODE.md), Fachbegriffe in [`CONTEXT.md`](../CONTEXT.md), Entscheidungen in [`adr/`](adr/).

> Stand: `conversation/` und `web/` umgesetzt, `./mvnw verify` grün (ohne LLM-Server). Die Thymeleaf-Templates
> baut das Frontend-Team; die Controller liefern nur View-Namen und Model-Attribute (siehe unten).

## Überblick

```
Browser (Dashboard, HTMX, EventSource)
   │ HTTP auf 127.0.0.1:9090
   ▼
web/LocalOnlyFilter ── Host-/Origin-/Referer-Prüfung (403)
   ▼
web/ChatController ──► assistant/AssistantService.stream(id, text)  ──► Flux<AssistantEvent> ──► SSE
   │        └────────► conversation/ConversationService  (speichert Nutzernachricht, aus Done die Antwort + tool_call)
web/ToolController ───► tools/ToolRegistry, tools/ConfirmationGate
web/KnowledgeController ► knowledge/DocumentIngestionService, FactMemory
web/SettingsController ► settings/SettingsService + assistant/ChatProviderRegistry (Verfügbarkeit, Modelle)
web/EventsController ──► DashboardEvents ◄── @EventListener TimerExpiredEvent (tools/)
web/DashboardController, HTMXFragmentController ► Seiten und HTMX-Fragmente (Model-Attribute via ViewModels)
web/ApiExceptionHandler ► einheitliche Fehler (ProblemDetail)
```

## Paket `conversation/`

| Klasse | Verantwortung |
|---|---|
| `Conversation` | Entity `conversation` (`id`, `title`, `created_at`, `updated_at`). |
| `Message` | Entity `message` (`conversation_id`, `role`, `content`, `language`, `provider`, `model`, `stt_ms`, `llm_ms`, `tts_ms`, `created_at`). |
| `ToolCall` | Entity `tool_call` (`message_id`, `tool_name`, `arguments`, `result`, `created_at`). |
| `Role` | `enum { USER, ASSISTANT }`. |
| `ConversationRepository` / `MessageRepository` / `ToolCallRepository` | Spring-Data-Repositories (je eine Datei). |
| `ConversationService` | `@Transactional`-Fachlogik, siehe unten. |
| `ConversationNotFoundException` | Unbekannte Konversation → 404 in `web/`. |

Verweise zwischen den Entities sind reine Id-Spalten (`conversationId`, `messageId`), keine JPA-Beziehungen;
das Löschen übernimmt die Datenbank per `ON DELETE CASCADE`.

```java
// ConversationService
public Conversation create(@Nullable String title);            // ohne Titel: "Neue Unterhaltung"
public Conversation rename(long id, String title);              // IllegalArgumentException | ConversationNotFoundException
public void delete(long id);                                    // Konversation (Cascade: Nachrichten, Tool-Aufrufe) + ChatMemory.clear(id)
public Conversation get(long id);
public List<Conversation> list();                               // updated_at desc
public List<MessageView> messages(long conversationId);         // älteste zuerst, inkl. ToolCalls
public Message saveUserMessage(long conversationId, String text, @Nullable String language, @Nullable Long sttMs);
public MessageView saveAssistantMessage(long conversationId, AssistantEvent.Done done);
public void recordTtsMillis(long messageId, long ttsMs);        // für den Sprachpfad (voice/); unbekannte Id: log.warn, keine Exception
public static String deriveTitle(String text);
```

Regeln:

- Die **erste** Nutzernachricht einer Konversation mit dem Standardtitel benennt sie: Whitespace zusammengefasst,
  an einer Wortgrenze auf 60 Zeichen gekürzt, `…` angehängt. Kein LLM-Aufruf. Ein manuell vergebener Titel bleibt.
- `saveAssistantMessage` übernimmt `provider`, `model`, `llm_ms` aus `Done` und legt je `ToolCallRecord` eine
  `tool_call`-Zeile an. `language` der Antwort = Sprache der letzten Nutzernachricht.
- `delete` entfernt auch den Spring-AI-Chat-Verlauf (`SPRING_AI_CHAT_MEMORY`) **nach dem Commit** (`afterCommit`; ein Fehler dabei wird mit `log.error` geloggt, die Konversation bleibt gelöscht) über das `ChatMemory`-Bean des
  KI-Kerns (`ChatMemory.clear(String.valueOf(id))`).
- Nachrichten vom Typ System/Tool werden nicht gespeichert (`Role` hat nur `USER`/`ASSISTANT`).
- Antworten, die der Client durch Abbruch nicht mehr abholt, werden nicht gespeichert (kein `Done`).

## Paket `settings/`

`SettingsService` (Vertrag `current()` → `SettingsSnapshot` unverändert) wurde überarbeitet:

- **Cache nur nach Commit:** `current()` liefert einen im Speicher gehaltenen Snapshot. Er wird erst in `afterCommit` gesetzt
  (ohne aktive Transaktion sofort); nach einem Rollback bleibt der alte Stand. `getSettings()` überschreibt den Cache beim
  bloßen Lesen nicht. Schreiber und das erstmalige Laden laufen unter einem `ReentrantLock` (inkl. Commit).
- **Modellliste außerhalb von Lock und Transaktion:** `update(AssistantSettings)` ist nicht `@Transactional`, holt die
  Modellliste (Netzwerk, bis 2 s) zuerst, prüft, und ruft dann `store(...)` (transaktional, über Self-Proxy) auf.
- **Leere Modellliste** (Provider nicht erreichbar): Speichern nur, wenn Provider **und** Modell unverändert sind
  (z. B. nur Prompt/Temperatur), sonst `IllegalArgumentException` „Modellliste von <Provider> nicht abrufbar – Modell kann
  nicht geprüft werden“ (+ `log.warn`).
- `fallbackModel(ChatProvider)`: Ollama → `spring.ai.ollama.chat.options.model`, Claude → erstes aus `jarvis.assistant.anthropic-models`.

`web/` nutzt es über `SettingsWebService` (Teil-Updates). Provider-Wechsel ohne Modell → erstes Modell der Liste des neuen
Providers; ist die Liste leer, `fallbackModel` (+ `log.warn`), sonst `400`; nie das Modell des alten Providers. Beim Wechsel auf
Claude wird eine nicht mitgegebene Temperatur > 1 auf 1.0 begrenzt (`log.info`); eine explizit zu hohe Temperatur ergibt `400`.

## Paket `web/`

| Klasse | Verantwortung |
|---|---|
| `ChatController` | `/api/conversations/**`: CRUD, Nachrichten, SSE-Chat-Stream. |
| `ToolController` | `/api/tools`: Liste, Update, Freigabe (`/confirmations/{id}`). |
| `KnowledgeController` | `/api/knowledge/**`: Dokumente und Fakten. |
| `SettingsController` | `/api/settings`: lesen, ändern, zurücksetzen. |
| `EventsController` / `DashboardEvents` | `GET /api/events`, dauerhafter SSE-Kanal; `@EventListener` für `TimerExpiredEvent`. |
| `DashboardController` | Ganze Seiten (`/`, `/chat/{id}`, `/settings`, `/tools`, `/knowledge`, `/status`). |
| `HTMXFragmentController` | `/fragments/**`: HTML-Schnipsel für HTMX. |
| `ViewModels` | befüllt das Model für Seiten **und** Fragmente (gleiche Attributnamen). |
| `SettingsWebService` | baut `SettingsView`, merged Teil-Updates. |
| `Dtos` / `SsePayloads` | Ein-/Ausgabe-Typen der JSON-API bzw. der SSE-Events. Entities werden nie direkt ausgeliefert. |
| `LocalOnlyFilter` | Host-/Origin-/Referer-Prüfung (siehe Sicherheit). |
| `JarvisWebProperties` | `jarvis.web.*`. |
| `ApiExceptionHandler` | `@RestControllerAdvice`, Fehler als `application/problem+json`. |

### Endpoints (JSON)

Alle zustandsändernden Requests brauchen einen gültigen `Origin`- oder `Referer`-Header (Browser senden ihn von selbst).
Fehler haben immer die Form `{"type":"about:blank","title":"Not Found","status":404,"detail":"…"}` – `detail` ist für Nutzer lesbar.

| Methode, Pfad | Request | Response |
|---|---|---|
| `GET /api/conversations` | – | `200 [ConversationDto]`, `updatedAt` absteigend |
| `POST /api/conversations` | optional `{title?}` | `201` + `Location`, `ConversationDto` |
| `GET /api/conversations/{id}` | – | `200 ConversationDto`; `404` |
| `PUT /api/conversations/{id}` | `{title}` (nicht leer, max. 200) | `200 ConversationDto`; `400`; `404` |
| `DELETE /api/conversations/{id}` | – | `204`; `404`; `409` solange eine Antwort gestreamt wird |
| `GET /api/conversations/{id}/messages` | – | `200 [MessageDto]` (älteste zuerst, mit `toolCalls`); `404` |
| `POST /api/conversations/{id}/messages` | `{text, language?, sttMs?}` | `200 text/event-stream` (siehe SSE); `400` leerer/zu langer Text (max. 8000); `404`; `409` wenn für die Konversation schon ein Stream läuft |
| `GET /api/tools` | – | `200 [{name, description, enabled, requiresConfirmation}]` |
| `PUT /api/tools/{name}` | `{enabled, requiresConfirmation}` | `200` aktualisiertes Tool; `400` (unbekannt oder Bestätigung bei Pflicht-Tool abgeschaltet) |
| `POST /api/tools/confirmations/{id}` | `{approved}` | `204`; `404` unbekannte/abgelaufene Id; `400` ungültige UUID/Body |
| `GET /api/knowledge/documents` | – | `200 [DocumentDto]` |
| `POST /api/knowledge/documents` | multipart, Feld `file` | `201` + `DocumentDto`; Indexierungsfehler stehen in `status=FAILED` + `statusMessage` (Antwort bleibt `201`, wird mit WARN geloggt); `400` leere Datei/kein Feld; `413` zu groß |
| `DELETE /api/knowledge/documents/{id}` | – | `204` (Zeile **und** Vektoren); `404` |
| `GET /api/knowledge/facts` | – | `200 [FactDto]` |
| `DELETE /api/knowledge/facts/{id}` | – | `204`; `404` |
| `GET /api/settings` | – | `200 SettingsView` |
| `PUT /api/settings` | `SettingsRequest`, alle Felder optional (`null`/fehlend = unverändert; `voiceDe`/`voiceEn` `""` = Standardstimme) | `200 SettingsView`; `400` mit Meldung (z. B. Modell/Temperatur ungültig) |
| `POST /api/settings/reset` | – | `200 SettingsView` mit den Defaults |
| `GET /api/events` | – | `text/event-stream`, dauerhaft (siehe SSE) |

Weitere Fehlerfälle überall: `403` (Host/Origin), `503` (`ProviderUnavailableException`), `502` (`KnowledgeException`),
`500` (unerwartet; wird geloggt, die Antwort nennt keine Details).

DTOs (Zeitstempel ISO-8601, lokale Zeit):

```
ConversationDto { id, title, createdAt, updatedAt }
MessageDto      { id, conversationId, role: USER|ASSISTANT, content, language, provider: OLLAMA|ANTHROPIC|null, model,
                  sttMs, llmMs, ttsMs, createdAt, toolCalls: [ToolCallDto] }
ToolCallDto     { id, toolName, arguments, result, createdAt }
DocumentDto     { id, filename, chunks, status: PENDING|INDEXED|FAILED, statusMessage, indexedAt, createdAt }
FactDto         { id, content, sourceMessageId, createdAt }
SettingsDto     { provider, model, systemPrompt, temperature, thinkMode, voiceDe, voiceEn, ttsEnabled, updatedAt }
SettingsView    { settings: SettingsDto, available: {OLLAMA: bool, ANTHROPIC: bool},
                  models: {OLLAMA: [String], ANTHROPIC: [String]}, defaultSystemPrompt }
```

`available.ANTHROPIC` heißt nur „Key vorhanden“; `models.OLLAMA` ist leer, wenn der LLM-Server nicht erreichbar ist.

### SSE-Chat-Stream (`POST /api/conversations/{id}/messages`)

Antwort `text/event-stream`; Eventname = `AssistantEvent.sseName()`, Nutzdaten (`data:`) sind einzeilige JSON-Objekte.
Der Stream endet nach genau einem `done` **oder** `error`. Der Browser muss `fetch` mit Stream-Reader benutzen
(`EventSource` kann nicht POSTen).

| Event | Nutzdaten | Bedeutung |
|---|---|---|
| `token` | `{"text": "…"}` | Text-Chunk der Antwort |
| `tool` | `{"toolName": "…", "arguments": "<JSON-String>"}` | Tool wird aufgerufen |
| `confirm` | `{"requestId": "<uuid>", "toolName": "…", "arguments": "<JSON-String>"}` | Nutzer muss `POST /api/tools/confirmations/{requestId}` beantworten (Timeout `jarvis.tools.confirmation-timeout`) |
| `warning` | `{"message": "…"}` | nicht fatal, z. B. RAG nicht verfügbar |
| `done` | `{"messageId": 42, "text": "…", "provider": "OLLAMA", "model": "qwen3:8b", "llmMs": 842, "toolCalls": [ToolCallDto]}` | Antwort ist **gespeichert**; `messageId` ist die Id der Assistenten-Nachricht; `provider=ANTHROPIC` → „☁ Cloud“-Badge |
| `error` | `{"message": "…"}` | Nutzertext; Nutzernachricht bleibt gespeichert, Antwort nicht |

Verhalten:

- Die Nutzernachricht wird **vor** dem Stream gespeichert (Fehler davor sind normale `400`/`404`-Antworten, auch bei `Accept: text/event-stream`).
- Bricht der Client ab (Tab zu, `AbortController`), wird die Modell-Anfrage abgebrochen; es wird keine Teilantwort gespeichert.
- Schlägt das Speichern der Antwort fehl, kommt `error` statt `done`.
- Maximale Dauer `jarvis.web.sse-timeout` (Default 10 min), danach kommt genau ein `error` („Zeitüberschreitung …“).
- Endet der Antwort-Flux ohne `done`/`error`, kommt `error` („Antwort unvollständig beendet“).
- Alle 15 s wird ein SSE-Kommentar (`:keepalive`) gesendet; er hält Proxies offen und lässt abgebrochene Clients früh erkennen (dann Abbruch der Modell-Anfrage, INFO-Log).
- Pro Konversation läuft höchstens ein Stream (zweites `POST` und `DELETE` währenddessen: `409` „Antwort läuft noch“).

### Dashboard-Events (`GET /api/events`)

`EventSource('/api/events')`; bleibt offen, kein Timeout, Reconnect macht der Browser. Höchstens 20 gleichzeitige Clients (der älteste wird geschlossen); alle 30 s ein `:keepalive`-Kommentar, der tote Verbindungen entfernt.

| Event | Nutzdaten |
|---|---|
| `ready` | `{}` direkt nach dem Verbinden |
| `timer` | `{"timerId": "<uuid>", "label": "Nudeln", "minutes": 10, "expiredAt": "2026-01-01T12:00:00Z"}` – ein Timer ist abgelaufen (an alle Clients) |

Tote Clients werden beim nächsten Senden entfernt. Die TTS-Ansage des Timers gehört zu `voice/` (nicht hier).

### Seiten und Fragmente (Model-Attribute für das Frontend)

Templates liegen unter `src/main/resources/templates/`. DTOs sind Records; in Thymeleaf/SpEL sind sie als normale
Properties lesbar (`${c.title}`; durch Test abgesichert).

**Ganze Seiten** (`DashboardController`)

| Pfad | View | Model-Attribute |
|---|---|---|
| `GET /`, `/chat.html` | `chat` | `conversations`, `activeConversationId` (null), `settings`, `available`, `models`, `providers`, `defaultSystemPrompt`, `cloudActive` |
| `GET /chat/{id}` | `chat` | wie oben, zusätzlich `activeConversation`, `messages`; unbekannte Id → Redirect auf `/` mit Flash-Attribut `error` („Unterhaltung nicht gefunden“) |
| `GET /settings` | `settings` | `settings`, `available`, `models`, `providers`, `defaultSystemPrompt`, `cloudActive` |
| `GET /tools` | `tools` | `tools` |
| `GET /knowledge` | `knowledge` | `documents`, `facts` |
| `GET /status` | `status` | `status` (`StatusReport`, siehe Abschnitt `status/`) sowie `settings`, `available`, `models`, `providers`, `defaultSystemPrompt`, `cloudActive`; Logs per HTMX von `/fragments/logs` |

**Attribut-Typen**

| Attribut | Typ |
|---|---|
| `conversations` | `List<ConversationDto>` (neueste Aktivität zuerst) |
| `activeConversationId` | `Long` oder `null` |
| `activeConversation` | `ConversationDto` |
| `messages` | `List<MessageDto>` (älteste zuerst, mit `toolCalls`) |
| `settings` | `SettingsDto` |
| `available` | `Map<ChatProvider,Boolean>` |
| `models` | `Map<ChatProvider,List<String>>` |
| `providers` | `ChatProvider[]` (`OLLAMA`, `ANTHROPIC`) |
| `defaultSystemPrompt` | `String` |
| `cloudActive` | `boolean`, `true` wenn Claude der aktive Provider ist |
| `tools` | `List<ToolInfo>` (`name`, `description`, `enabled`, `requiresConfirmation`) |
| `documents` | `List<DocumentDto>` (neueste zuerst) |
| `facts` | `List<FactDto>` (neueste zuerst) |

**HTMX-Fragmente** (`HTMXFragmentController`, `/fragments/**`). Zustandsändernde Endpoints führen die Änderung aus und
liefern das aktualisierte Fragment. Fehler kommen als **HTTP 200** mit Model-Attribut `error` (String), weil HTMX 4xx
nicht in die Seite übernimmt. Jede mutierende Methode fängt Fehler selbst ab (jedes Fragment braucht sein eigenes Model):
Validierungsfehler/unbekannte Id → `log.warn` ohne Stacktrace, andere `RuntimeException`s (`DataAccessException`, …) →
`log.error` mit Stacktrace; `error` enthält die Meldung (bei `ProviderUnavailableException`/`KnowledgeException`) bzw.
„Vorgang fehlgeschlagen, Details im Log“. `MaxUploadSizeExceededException` tritt vor dem Controller auf und kommt global als 413.

| Methode, Pfad (Parameter) | Fragment | Model-Attribute |
|---|---|---|
| `GET /fragments/conversations` (`active?`) | `fragments/conversation-list :: list` | `conversations`, `activeConversationId` |
| `POST /fragments/conversations` | dito | dito; `activeConversationId` = neue Id |
| `PUT /fragments/conversations/{id}` (`title`, `active?`) | dito | dito; ggf. `error` |
| `DELETE /fragments/conversations/{id}` (`active?`) | dito | dito; war sie aktiv: `activeConversationId` = null |
| `GET /fragments/settings` | `fragments/settings-form :: form` | `settings`, `available`, `models`, `providers`, `defaultSystemPrompt`, `cloudActive` |
| `POST /fragments/settings` (`provider`, `model`, `systemPrompt`, `temperature`, `thinkMode`, `voiceDe`, `voiceEn`, `ttsEnabled`) | dito | dito; `saved=true` oder `error` (gespeicherte Werte bleiben). Nicht gesendete Checkboxen = `false`, leere Stimme = Standard |
| `GET /fragments/tools` | `fragments/tool-list :: list` | `tools` |
| `POST /fragments/tools/{name}` (`enabled`, `requiresConfirmation`) | dito | `tools`; ggf. `error` |
| `GET /fragments/documents` | `fragments/document-list :: list` | `documents` |
| `POST /fragments/documents` (multipart `file`) | dito | `documents`, `uploaded` (`DocumentDto`, Status `INDEXED`/`FAILED`), ggf. `error` (leere Datei, bei `FAILED` = `statusMessage`, Ausnahmen) |
| `DELETE /fragments/documents/{id}` | dito | `documents`; unbekannte Id/Fehler → `error` |
| `GET /fragments/facts` | `fragments/fact-list :: list` | `facts` |
| `DELETE /fragments/facts/{id}` | dito | `facts`; unbekannte Id/Fehler → `error` |
| `GET /fragments/status` | `fragments/status :: status` | `status` (`StatusReport`) |
| `GET /fragments/logs` (`level?`) | `fragments/logs :: list` | siehe Abschnitt „Log-Puffer“ |

Details zu `StatusReport` im Abschnitt `status/`.

HTMX-Requests müssen von der Seite aus laufen (Browser senden `Origin`/`Referer` automatisch); die Fragmente sind
nicht für Aufrufe von außerhalb gedacht.

### Sicherheit

Das Dashboard hat keine Authentifizierung und bindet an `127.0.0.1`. `LocalOnlyFilter` (läuft vor allem anderen) schützt
gegen DNS-Rebinding und CSRF:

- **Alle** Requests: Host-Header muss in `jarvis.web.allowed-hosts` stehen (Default `localhost`, `127.0.0.1`; Port egal,
  Groß-/Kleinschreibung egal), sonst `403`.
- `POST`/`PUT`/`PATCH`/`DELETE`: `Origin` (sonst `Referer`) muss `http(s)://<erlaubter Host>:<eigener Port>` sein (eigener Port =
  `request.getLocalPort()`; ohne Port gilt 80 bzw. 443). Fehlen beide → `403`; ist `Origin` gesetzt, entscheidet er allein
  (`null`, fremde Hosts, fremde Ports, andere Schemata → `403`).
- Zusätzliche Origins für ein Frontend-Dev-Setup: `jarvis.web.allowed-origins` (Liste `Schema://Host:Port`, Default leer).
- `Sec-Fetch-Site` (falls vorhanden) bei zustandsändernden Requests: `same-origin`/`none` erlaubt, `cross-site` und
  unbekannte Werte → `403`, `same-site` nur für Origins aus `allowed-origins`.
- Jede Antwort (auch die 403 des Filters) trägt `X-Frame-Options: DENY`, `Content-Security-Policy: frame-ancestors 'none';
  base-uri 'self'; form-action 'self'` (bewusst ohne `script-src`/`style-src`, damit Thymeleaf/HTMX/WebJars/eigenes JS laufen),
  `X-Content-Type-Options: nosniff` und `Referrer-Policy: same-origin` (nicht `no-referrer`, der Filter braucht den Referer als Fallback).
- Die Antwort nennt keine Gründe (`"Anfrage nicht erlaubt"`), der Grund steht im Log (WARN); abgelehnte Host/Origin/Referer-Werte
  werden dort auf 100 Zeichen gekürzt und Steuerzeichen durch `_` ersetzt (Log-Injection).
- Nicht-Browser-Clients (curl, Skripte) müssen deshalb `Origin: http://localhost:9090` mitsenden.
- Die H2-Konsole bleibt aus (siehe `CODE.md`).

### Fehler-Mapping (`ApiExceptionHandler`)

| Ausnahme | Status |
|---|---|
| `ConversationNotFoundException`, `ConfirmationNotFoundException` | 404 |
| `IllegalArgumentException` (u. a. aus `SettingsService`, `ToolRegistry`, `ConversationService`) | 400 mit Meldung |
| Bean-Validation, kaputtes JSON, falscher Pfadparameter-Typ, fehlender Multipart-Teil | 400 |
| zu große Datei (`MaxUploadSizeExceededException`) | 413 |
| `StreamActiveException` (laufender Chat-Stream) | 409 |
| `ProviderUnavailableException` | 503 (WARN-Log) |
| `KnowledgeException` | 502 |
| Standard-Spring-MVC-Fehler (405, 415, 404 …) | wie von Spring, als ProblemDetail |
| abgebrochene Client-Verbindung (`AsyncRequestNotUsableException`, `IOException` Broken pipe/Connection reset) | keine Antwort, kein ERROR-Log |
| alles andere | 500 `"Interner Fehler"`, Stacktrace nur im Log |

Fehlerantworten haben immer `Content-Type: application/problem+json`, auch wenn der Client `Accept: text/event-stream` sendet.

### Log-Puffer

`status/LogBufferAppender` (Logback-`AppenderBase`, eingebunden über `src/main/resources/logback-spring.xml` am Root, Level INFO,
zusätzlich zu `CONSOLE` mit den Spring-Boot-Defaults) hält die letzten `MAX_LINES = 200` formatierten Zeilen in einem
statischen, synchronisierten Ringpuffer. Statischer Zugriff: `getLines()`, `getLines(Level minLevel)` (mindestens dieses Level),
`clear()`; Einträge sind `LogLine(timestamp, level, text)`. Pattern per logback-Property `LOG_BUFFER_PATTERN` änderbar
(Default `%d{HH:mm:ss.SSS} %-5level %logger{20} : %msg%n%ex{short}`). Der Appender wirft nie Exceptions.

`web/LogController`, Parameter `level` (optional, `TRACE|DEBUG|INFO|WARN|ERROR`, egal welche Schreibweise, leer = alle):

| Endpoint | Antwort |
|---|---|
| `GET /fragments/logs?level=` | View `fragments/logs :: list`; Attribute `lines` (Liste `LogEntry(time, level, text)`, älteste zuerst), `level` (normalisiert, `""` = alle); bei ungültigem Level HTTP 200 mit `error` und leerer `lines` |
| `GET /api/logs?level=` | JSON-Liste `[{time, level, text}]`; ungültiges Level → 400 (ProblemDetail) |

`text` ist die komplette Zeile (enthält bereits die Zeit). Logzeilen können Fremdinhalte enthalten: im Template nur `th:text`
(Thymeleaf escaped), nie `th:utext`. Tests: `status/LogBufferAppenderTest`, `web/LogControllerTest`.

## Paket `status/`

| Klasse | Verantwortung |
|---|---|
| `VoiceHealthClient` | `GET {jarvis.voice.base-url}/health` mit Header `X-Jarvis-Token` (weggelassen, wenn `jarvis.voice.token` leer), Connect/Read-Timeout je 2 s, Ergebnis 3 s gecacht (die Statusseite pollt alle 5 s). Wirft nie; Fehler → `VoiceHealth.up=false`. Token wird nie geloggt oder ausgegeben. Parst tolerant (siehe Format unten). |
| `VoiceHealth` | Record: `up`, `message`, `models`, `gpuName`, `gpuMemoryUsedBytes/TotalBytes`, `gpuUtilization` (0..1), `checkedAt`. |
| `VoiceHealthIndicator` | Actuator-Komponente `voice` (Boot-4-Paket `org.springframework.boot.health.contributor`); nutzt den gecachten Client, Details ohne Secrets. |
| `OllamaHealthIndicator` | Komponente `ollama` über `ChatProviderRegistry.available()/models(OLLAMA)` (bereits 4 s gecacht, kein zweiter Probe). Nicht erreichbar → `DOWN`. |
| `ClaudeHealthIndicator` | Komponente `claude`: Key vorhanden → `UP` („konfiguriert“), sonst `UNKNOWN` („kein Key“), nie `DOWN`. `UNKNOWN` verschlechtert den Gesamtstatus nicht. Kein Netzwerkaufruf. |
| `MetricsService` | `snapshot()` (Vertrag für `SystemTools`), `recordStt/recordLlm/recordTts(ms)`, `lastLatencies()`. CPU/RAM über OSHI (CPU-Last per Tick-Differenz zur vorigen Messung, höchstens 1×/s neu berechnet, nie blockierend; Fallback JMX), GPU-Speicher aus `VoiceHealthClient`, sonst `null`. Micrometer-Timer `jarvis.latency` mit Tag `stage=stt|llm|tts` (sichtbar unter `/actuator/metrics/jarvis.latency?tag=stage:llm`). |
| `LatencyRestorer` | Lädt bei `ApplicationReadyEvent` die letzten Latenzen aus `message` (jüngste Zeile mit `stt_ms`/`llm_ms`/`tts_ms`); Timer bleiben unberührt, bereits gemessene Werte werden nicht überschrieben. |
| `StatusService` | baut den `StatusReport` für Seite, Fragment und JSON (inkl. DB-Ampel per `Connection.isValid`). |

**Verdrahtung der Latenzen:** `web/ChatController` ruft `recordStt(sttMs)` (wenn `ChatRequest.sttMs` gesetzt) und `recordLlm(done.llmMillis())`
direkt nach dem Persistieren von `Done`. `recordTts(ms)` ruft `voice/` auf, sobald die Sprachausgabe gemessen ist (zusammen mit
`ConversationService.recordTtsMillis`); das ist noch nicht verdrahtet.

### Endpoints und Model-Attribute

| Pfad | Antwort |
|---|---|
| `GET /api/status` | JSON `StatusReport` |
| `GET /fragments/status` | View `fragments/status :: status`, Attribut `status` |
| `GET /status` (Seite) | View `status`, Attribut `status` plus Einstellungs-Attribute |
| `GET /actuator/health` | Actuator mit Details (`ollama`, `claude`, `voice`, `db`, …); `LocalOnlyFilter` (Host-Header) gilt auch für `/actuator/**` |

```
StatusReport { checkedAt, activeProvider: OLLAMA|ANTHROPIC,
               lights: [Light { key: ollama|claude|voice|db, name, state: UP|DOWN|UNKNOWN, message }],
               ollamaModels: [String],            // installierte Modelle (/api/tags); leer, wenn nicht erreichbar
               voiceModels: [String],
               gpu: Gpu { name, memoryUsedBytes, memoryTotalBytes, utilization 0..1 } | null,
               cpuLoad (0..1, <0 = unbekannt), usedMemoryBytes, totalMemoryBytes,
               latencies: { sttMs, llmMs, ttsMs },  // jeweils null = noch keine Messung
               metricsSource: oshi|jmx|unbekannt    // Quelle von CPU/RAM
}
```

Robustheit: `StatusService.report()` sichert jeden Teilwert einzeln ab. Wirft ein Indikator, wird die Ampel `DOWN`
(„Prüfung fehlgeschlagen (Klasse)“), Listen bleiben leer; geloggt wird nur beim Zustandswechsel (warn mit Stacktrace).
Ist der aktive Provider `ANTHROPIC` und Claude ohne Key (`UNKNOWN`), stuft die Statusseite die Claude-Ampel auf `DOWN`
(„aktiver Provider nicht verfügbar (kein API-Key)“); der Health-Indikator selbst bleibt `UNKNOWN`.
`VoiceHealthClient` unterscheidet „Token ungültig (HTTP 401/403)“, „HTTP &lt;code&gt;“ und „Timeout/Verbindung fehlgeschlagen“,
hängt „down seit &lt;Zeit&gt;“ an, wertet nicht lesbare/leere Antworten als `down` („Antwort nicht lesbar (kein voice-service?)“) und ruft das Netz
nur als Single-Flight ab (parallele Aufrufer teilen sich einen Request oder bekommen den letzten Wert). CPU-Last
NaN/negativ ist „unbekannt“ (-1). `LogBufferAppender.isActive()` zeigt, ob der Puffer aktiv ist.

Ampelfarbe: `UP` grün, `DOWN` rot, `UNKNOWN` grau (z. B. Claude ohne Key – kein Fehler). „Geladene“ Ollama-Modelle sind hier die
installierten (`/api/tags`); `/api/ps` (wirklich im VRAM geladen) wird nicht abgefragt, um keinen zweiten Probe zu bauen.
Ein Aufruf kann bis ca. 4 s dauern, wenn LLM-Server und voice-service nicht antworten (je 2 s Timeout, danach gecacht).

### Vorgeschlagenes `GET /health` des voice-service (für Steinwidder/Radaelli)

Authentifizierung: Header `X-Jarvis-Token` (ADR 0001). Jarvis liest tolerant; fehlende Felder bedeuten „unbekannt“.

```json
{
  "status": "ok",
  "models": {"stt": "faster-whisper large-v3", "tts": "piper"},
  "gpu": {
    "name": "NVIDIA GeForce RTX 4070",
    "memory_used_mb": 2048,
    "memory_total_mb": 12288,
    "utilization_percent": 35
  }
}
```

Akzeptiert werden außerdem: `models` als Liste oder String; `gpu` als Liste (erstes Element); `memory_used_bytes`/`memory_total_bytes`
statt `_mb` (MiB) bzw. `vram_used_*`/`vram_total_*`; `utilization` (0..1). Ein `status` außer `ok|up|healthy` zählt als `DOWN`, jeder
HTTP-Fehler und Timeout ebenfalls. Ohne GPU `gpu` weglassen oder `null`.

### Hinweise

- Die Log-Zeilen erscheinen im Dashboard: keine Secrets loggen, Escaping übernimmt Thymeleaf (`th:text`), siehe „Log-Puffer“.
- Ein `DOWN` von Ollama oder voice-service lässt `/actuator/health` mit HTTP 503 antworten, verhindert aber weder Start noch Tests
  (nichts im Start liest den Health-Status).

### Neue Konfiguration

```yaml
management:
  endpoint:
    health:
      show-details: always   # nur localhost erreichbar (LocalOnlyFilter)
# genutzt, unverändert: jarvis.voice.base-url (JARVIS_VOICE_URL), jarvis.voice.token (JARVIS_VOICE_TOKEN)
```

Keine neue Migration; neue Abhängigkeit `com.github.oshi:oshi-core:6.8.0` (Property `oshi.version` in `pom.xml`).

## Datenbank / Flyway

| Migration | Inhalt |
|---|---|
| `V1__init.sql` | KI-Kern (Brenner): `tool_setting`, `knowledge_document`, `memory_fact`, `SPRING_AI_CHAT_MEMORY` |
| `V2__assistant_settings.sql` | `assistant_settings` (Singleton `id = 1`) |
| `V3__conversation.sql` | `conversation`, `message`, `tool_call` |
| `V4__tool_call_status.sql` | `tool_call.status` (`OK`, `FAILED`, `NOT_EXECUTED`; Altbestand `OK`) |

`V3`: `message.conversation_id → conversation(id) ON DELETE CASCADE`, `tool_call.message_id → message(id) ON DELETE CASCADE`;
Indizes `idx_conversation_updated_at (updated_at DESC)`, `idx_message_conversation (conversation_id, created_at, id)`,
`idx_tool_call_message (message_id)`. `content`/`arguments`/`result` sind `CLOB` (`@Lob`). Bewusst **ohne** `CHECK` auf
`message.role` (siehe Hinweis unten); die Werte stellt das Enum `Role` sicher.

`SPRING_AI_CHAT_MEMORY` hat keinen FK auf `conversation` (`conversation_id` ist dort ein String); `ConversationService.delete`
räumt sie über `ChatMemory.clear` auf.

> Hinweis H2: In H2 2.4.240 funktionieren `CHECK`-Constraints nicht mehr, sobald die Verbindung geschlossen ist, die
> sie angelegt hat (jeder `INSERT` schlägt mit „Check constraint invalid“ fehl). Betrifft `V1` (`SPRING_AI_CHAT_MEMORY.type`)
> in nicht gepoolten Verbindungen, z. B. in `@DataJpaTest`; im gepoolten Betrieb (Hikari) hat es im Test funktioniert.
> Neue Migrationen deshalb ohne `CHECK`.

## Konfiguration

```yaml
jarvis:
  web:
    allowed-hosts: [localhost, 127.0.0.1]   # Host-Header + Origin/Referer (LocalOnlyFilter)
    allowed-origins: []                     # zusätzliche Origins (Schema://Host:Port) für Frontend-Dev-Setup
    sse-timeout: 10m                        # maximale Dauer eines Chat-Streams
```

## Tests

| Test | Prüft |
|---|---|
| `conversation/ConversationServiceTest` (`@DataJpaTest`, Flyway V1–V3, `ddl-auto=validate`) | Schema passt zu den Entities, Titel-Ableitung, Sortierung, Assistenten-Nachricht + Tool-Aufrufe, Cascade, `ChatMemory`-Aufräumen |
| `web/ChatControllerTest` (`@WebMvcTest`, `AssistantService` gefakt) | CRUD, 400/404, SSE-Ablauf, Persistieren bei `done`, `error`, Speicherfehler, Client-Abbruch, 403 ohne Origin |
| `web/ApiControllersTest` (`@WebMvcTest`) | Bestätigung (204/404/400), Tools, Upload/Löschen, Settings (Teil-Update, Provider-Wechsel, 400), Timer-Event + tote Emitter |
| `web/LocalOnlyFilterTest` | Host-/Origin-/Referer-Regeln inkl. Angriffsmuster |
| `web/PageControllersTest` | View-Namen und Model-Attribute der Seiten und Fragmente (inkl. Status), `GET /api/status`, SpEL-Zugriff auf Records |
| `status/VoiceHealthClientTest` (`MockRestServiceServer`) | Token-Header gesetzt/weggelassen, Fehler/Timeout → DOWN ohne Token in der Meldung, Cache, tolerantes Parsen, Indicator |
| `status/MetricsServiceTest` | Latenz-Timer und letzte Werte, Snapshot ohne/mit GPU, Ollama-/Claude-Indicator |

## Abweichungen vom UML-Entwurf

- `Repositories.java` gibt es nicht mehr; drei Repository-Dateien. `Message.conversationId` statt `@ManyToOne`, kein `ToolCallStatus`, `Role` nur `USER`/`ASSISTANT`.
- Der `ChatController` ist aufgeteilt (`ToolController`, `KnowledgeController`, `SettingsController`, `EventsController`), statt alles in einer Klasse.
- `GlobalExceptionHandler` heißt `ApiExceptionHandler`; `ChatEvent` aus dem Entwurf ist `AssistantEvent`.
- Titel-Suche (`findByTitleContainingIgnoreCase`), `Message.getTotalLatencyMs()` fehlen noch.
- `status/`: `VoiceServiceClient` aus dem Entwurf gehört zu `voice/`; `status/` hat einen eigenen `VoiceHealthClient`. `SystemStatus` heißt `StatusReport`
  (`StatusService`), `OllamaHealthIndicator` nutzt die `ChatProviderRegistry` statt eines eigenen `RestClient`; zusätzlich `ClaudeHealthIndicator`.
