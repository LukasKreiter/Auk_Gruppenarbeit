# Jarvis – Lokaler Sprachassistent mit Spring Boot, Ollama und Whisper

## Kontext

Ziel ist ein „Jarvis“-artiger Assistent, der im eigenen Netz läuft:
Sprache rein → LLM → Sprache raus, plus ein Web-Dashboard, das den Assistenten
verwaltet (Chat-Verlauf, Modell/Prompt, Systemstatus, Tools, Wissen). Die Spring-Boot-
Anwendung `jarvis` läuft auf dem Arbeitsplatz-PC und ist das Herzstück: sie orchestriert alle
Dienste und liefert das Dashboard. LLM (Ollama) und voice-service laufen auf einem separaten
GPU-Server im LAN. Optional kann statt des lokalen Modells ein Cloud-Modell (Claude) gewählt werden.

Weitere Doku: [`CONTEXT.md`](CONTEXT.md) (Fachbegriffe), [`docs/adr/`](docs/adr/) (Entscheidungen),
[`docs/CODE.md`](docs/CODE.md) (Code-Doku Backend KI-Kern).

## Einteilung

| Name | Rolle |
|---|---|
| Kreiter | Server Aufbau (LLM-Server: Ollama, voice-service-Host, Firewall) |
| Brenner | Backend – KI-Kern: `assistant/`, `knowledge/`, `tools/` ([Details](#backend--ki-kern-brenner)) |
| Hiebler | Backend – Infrastruktur: `conversation/`, `settings/`, `status/`, `web/` |
| Zheng, Zugaj | Frontend |
| Steinwidder | TTS |
| Radaelli | STT |

## Getroffene Entscheidungen

| Thema | Entscheidung | Begründung |
|---|---|---|
| LLM-Runtime | **Ollama** | REST-API, nativer Spring-AI-Support, Modelle per `ollama pull` wechselbar |
| LLM-Hosting | **separater GPU-Server** (Ollama + voice-service) | GPU-Last weg vom PC, löst das VRAM-Problem ([ADR 0001](docs/adr/0001-llm-auf-separatem-server.md)) |
| Chat-Provider | **Ollama oder Claude, pro Request umschaltbar** | Cloud-Modell für schwierige Fragen; Wechsel in den Einstellungen, kein Neustart; Key nur per `ANTHROPIC_API_KEY` ([ADR 0002](docs/adr/0002-umschaltbarer-chat-provider.md)) |
| Cloud-Fallback | **keiner** | private Daten gehen nie ungefragt in die Cloud ([ADR 0005](docs/adr/0005-kein-automatischer-cloud-fallback.md)) |
| Chat-Modell (Start) | **`qwen3:8b`** (Fallback `llama3.1:8b`) | DE+EN stark, Tool-Calling in Ollama unterstützt, passt in ≥ 8 GB VRAM quantisiert. Beim Setup prüfen, ob ein aktuellerer Nachfolger in der Ollama-Library liegt |
| Embedding-Modell | **`bge-m3`** (immer Ollama, auch im Claude-Modus) | mehrsprachig (DE/EN), für RAG und Fakten-Gedächtnis; Anthropic hat keine Embeddings ([ADR 0003](docs/adr/0003-embeddings-immer-bge-m3.md)) |
| STT | **faster-whisper** (`large-v3-turbo`, CUDA) | beste Qualität für Deutsch, auto-Spracherkennung, läuft offline |
| TTS | **Piper** (piper1-gpl) | lokale Stimmen `de_DE-thorsten`, `en_US-lessac`; CPU reicht |
| STT+TTS-Hosting | **ein Python-Sidecar `voice-service`** (FastAPI) | Audio/ML-Ökosystem ist Python; Spring Boot bleibt reines Java und spricht nur HTTP |
| Frontend | **Thymeleaf + HTMX + kleines Vanilla-JS-Modul** | ein Projekt, kein Node-Build; JS nur für Mikrofon (MediaRecorder), SSE-Streaming und Audio-Playback |
| Styling | Bootstrap 5 via WebJars | offline-fähig, kein CDN |
| DB | **H2 im File-Modus** + Flyway | null Konfiguration, H2-Konsole zum Debuggen; SQLite/Postgres später möglich |
| Vector-Store | Spring AI `SimpleVectorStore` (JSON-Datei) | reicht für einige hundert Chunks; Upgrade-Pfad: pgvector/Qdrant. Der generierte Azure-Starter fliegt raus ([ADR 0004](docs/adr/0004-simplevectorstore-statt-azure.md)) |
| Build | Java 21, Maven, Spring Boot 4.1.1 + Spring AI 2.0.1 | so von start.spring.io generiert; groupId `de.grauk`, Konfiguration in `application.yml` |
| Audio-Eingabe (MVP) | Push-to-Talk im Browser | einfachster Weg; Wake-Word wurde nicht gewünscht; Always-on-Listener als spätere Erweiterung |
| Netzwerk | `jarvis` nur auf `127.0.0.1`, keine Auth im MVP; LLM-Server per Firewall nur für den Jarvis-PC offen, voice-service zusätzlich mit Token | Ollama hat keine Auth ([ADR 0001](docs/adr/0001-llm-auf-separatem-server.md)); Basic-Auth für das Dashboard optional, falls später im LAN erreichbar |

## Zielarchitektur

```
 Browser (Dashboard, Thymeleaf/HTMX/JS)
   │  Mikrofon-Blob (webm/opus)  │ SSE (Token-Stream, Events)  │ WAV-Playback
   ▼                             ▼                             ▲
 ┌──────────────── jarvis (Spring Boot, 127.0.0.1:8080) – Arbeitsplatz-PC ────────────────┐
 │  web/        Thymeleaf-Seiten + HTMX-Fragmente + REST/SSE-Endpoints                    │
 │  assistant/  AssistantService, ChatProviderRegistry (Ollama | Claude), Advisors, Tools │
 │  voice/      VoiceServiceClient (HTTP → voice-service), Sprach-/Stimmenwahl            │
 │  tools/      @Tool-Beans + ToolRegistry (an/aus, Bestätigungspflicht)                  │
 │  knowledge/  Dokument-Ingestion, VectorStore (JSON-Datei), Fakten-Gedächtnis           │
 │  settings/   Provider, Modell, System-Prompt, Temperatur, Stimmen (persistiert)        │
 │  status/     HealthIndicators, Latenz-Metriken, Log-Ringpuffer                          │
 │  H2 (File) ── Konversationen, Nachrichten, Settings, Tool-Flags, Dokumente, Fakten     │
 └──────┬──────────────────────────────┬───────────────────────────────┬──────────────────┘
        │ Spring AI (HTTPS, optional)  │ Spring AI (HTTP, LAN)         │ HTTP + X-Jarvis-Token (LAN)
        ▼                              ▼                               ▼
  Anthropic API (Cloud)     ┌──────────── LLM-Server (GPU, Firewall: nur Jarvis-PC) ────────────┐
  claude-sonnet-5-5 …       │ Ollama (:11434)            voice-service (Python/FastAPI, :8090)  │
  nur wenn in Settings      │ qwen3:8b · bge-m3          POST /transcribe (faster-whisper, CUDA)│
  gewählt                   │                            POST /synthesize (Piper)               │
                            │                            GET  /health (Modelle, GPU via pynvml) │
                            └───────────────────────────────────────────────────────────────────┘
```

## Ablauf einer Sprachanfrage

1. Nutzer hält Mikro-Button / Leertaste → `MediaRecorder` nimmt auf (webm/opus).
2. Browser `POST /api/voice/transcribe` → Spring leitet an `voice-service /transcribe` → `{text, language}`; erkannter Text erscheint sofort im Chat (Nutzer sieht, was verstanden wurde).
3. Browser sendet den Text über den **gleichen** Chat-Endpoint wie Tastatureingaben: `POST /api/conversations/{id}/messages` → Spring streamt Antwort-Tokens per SSE.
   - `AssistantService` baut den Prompt: System-Prompt (aus Settings) + `MessageChatMemoryAdvisor` (Verlauf aus DB) + `QuestionAnswerAdvisor` (RAG-Kontext) + aktivierte Tools.
   - Tool-Aufrufe werden von Spring AI ausgeführt; das Dashboard bekommt ein SSE-Event „Tool X aufgerufen“.
4. Nach Stream-Ende: Browser `POST /api/voice/synthesize` mit dem Antworttext → Spring wählt Stimme nach Sprache (Java-Lib `lingua` erkennt DE/EN im Antworttext) → `voice-service /synthesize` → WAV → Browser spielt ab.
5. Nachricht + Antwort + Metadaten (Latenzen STT/LLM/TTS, verwendetes Modell, Tool-Aufrufe) landen in H2.

Text- und Sprachweg unterscheiden sich nur in Schritt 2 und 4 – die Chat-Pipeline ist identisch.

## Projektstruktur

```
Auk_Gruppenarbeit/
├── README.md                  Überblick + Setup-Anleitung (Ollama, Modelle, Python-Env, Start)
├── CONTEXT.md                 Fachbegriffe
├── docs/
│   ├── CODE.md                Code-Doku Backend KI-Kern
│   └── adr/                   Architekturentscheidungen (0001 …)
├── start-all.ps1              startet jarvis auf dem PC (Ollama + voice-service laufen auf dem LLM-Server)
├── jarvis/                    Spring Boot (Maven)
│   ├── pom.xml
│   └── src/main/java/de/grauk/jarvis/
│       ├── JarvisApplication.java   @SpringBootApplication (fehlt noch)
│       ├── assistant/         AssistantService, AssistantEvent, ChatProviderRegistry, ChatClientConfig, PromptTemplates
│       ├── conversation/      Conversation, Message (JPA), Repositories
│       ├── voice/             VoiceServiceClient, VoiceController, VoiceSelector
│       ├── tools/             ToolRegistry, ToolSetting (JPA), ConfirmationGate, builtin/*.java (@Tool)
│       ├── knowledge/         DocumentIngestionService, KnowledgeDocument, FactMemory, MemoryFact, VectorStoreConfig
│       ├── settings/          AssistantSettings (JPA), SettingsService
│       ├── status/            OllamaHealthIndicator, VoiceHealthIndicator, LogBufferAppender, MetricsService
│       └── web/               DashboardController, ChatController (SSE), HTMX-Fragment-Controller
│   └── src/main/resources/
│       ├── application.yml    LLM-Server-URL, Modelle, Claude-Modellliste, voice-service-URL, H2-Pfad, server.address=127.0.0.1
│       ├── db/migration/      Flyway V1__init.sql …
│       ├── templates/         layout.html, chat.html, settings.html, status.html, tools.html, knowledge.html, fragments/
│       └── static/js/         chat.js (Recorder, SSE, Playback)
└── voice-service/             Python 3.11+, FastAPI – läuft auf dem LLM-Server
    ├── pyproject.toml / requirements.txt   fastapi, uvicorn, faster-whisper, piper-tts, pynvml
    ├── app/main.py            Endpoints /transcribe, /synthesize, /health
    ├── app/stt.py             WhisperModel (large-v3-turbo, device=cuda, compute_type=int8_float16, vad_filter)
    ├── app/tts.py             Piper-Voices laden, Text → WAV
    ├── models/                Piper-Stimmen (.onnx + .json), Whisper-Cache
    └── tests/                 pytest mit Beispiel-WAV
```

## Datenmodell (H2, Flyway)

- `conversation` (id, title, created_at, updated_at)
- `message` (id, conversation_id, role, content, language, provider, model, stt_ms, llm_ms, tts_ms, created_at)
- `tool_call` (id, message_id, tool_name, arguments, result, created_at)
- `assistant_settings` (singleton: provider, model, system_prompt, temperature, voice_de, voice_en, tts_enabled, think_mode)
- `tool_setting` (tool_name, enabled, requires_confirmation)
- `knowledge_document` (id, filename, chunks, indexed_at, status)
- `memory_fact` (id, content, source_message_id, created_at)
- Spring-AI-Chat-Memory-Tabelle über `spring-ai-starter-model-chat-memory-repository-jdbc`

## Dashboard-Seiten

| Seite | Inhalt |
|---|---|
| **Chat** | Konversationsliste, Nachrichtenverlauf, Texteingabe, Push-to-Talk-Button, Token-Streaming, Tool-Aufruf-Badges, Bestätigungsdialog für Tools, „☁ Cloud“-Badge bei Claude, Audio-Playback, erkannte Sprache |
| **Einstellungen** | Provider-Auswahl Ollama/Claude (Claude ausgegraut ohne API-Key), Modell-Dropdown (Ollama live aus `/api/tags`, Claude aus Config), System-Prompt-Editor, Temperatur, Thinking-Modus an/aus, Stimmen DE/EN, TTS an/aus |
| **Status** | Ampel Ollama (LLM-Server) / Claude (Key vorhanden) / voice-service / DB, geladene Modelle, GPU-VRAM & Auslastung (aus `/health` des Sidecars via pynvml), CPU/RAM (OSHI), letzte Latenzen STT/LLM/TTS, Log-Ringpuffer (letzte 200 Zeilen, HTMX-Polling) |
| **Tools** | Liste aller `@Tool`-Beans mit Beschreibung, Toggle aktiv, Toggle „Bestätigung nötig“, letzte Aufrufe |
| **Wissen** | Dokument-Upload (PDF/DOCX/MD/TXT → Tika-Reader → TokenTextSplitter → bge-m3 → VectorStore), Index-Status, Liste gemerkter Fakten mit Löschen |

## Tools (erste Ausbaustufe)

| Tool | Zweck | Anmerkung |
|---|---|---|
| `getCurrentDateTime` | Datum/Uhrzeit | trivial, gutes Smoke-Test-Tool |
| `setTimer(minutes, label)` | Timer/Erinnerung | `TaskScheduler`; bei Ablauf SSE-Event + TTS-Ansage im Dashboard |
| `getWeather(city)` | Wetter | Open-Meteo (kostenlos, kein Key); einziges Tool mit Internet |
| `rememberFact(text)` | Langzeitgedächtnis | schreibt in `memory_fact` + VectorStore |
| `searchKnowledge(query)` | explizite Wissenssuche | ergänzt den automatischen RAG-Advisor |
| `openApplication(name)` | Programm starten | **Allowlist** in `application.yml`, standardmäßig „Bestätigung nötig“ |
| `getSystemStatus` | CPU/RAM/GPU | nutzt `MetricsService` |

Später: Web-Suche (SearXNG lokal), Kalender, Smart-Home, Dateisuche.

## Umsetzungsphasen

### Phase 0 – Umgebung & Skelett
- **LLM-Server:** Ollama installieren, `OLLAMA_HOST=0.0.0.0`, `ollama pull qwen3:8b` und `bge-m3`; VRAM mit `nvidia-smi` prüfen. Firewall: 11434 und 8090 nur vom Jarvis-PC.
- **VRAM-Kalibrierung (Server):** bei genau 8 GB → Whisper `int8` oder `medium`, ggf. `qwen3:4b`; bei ≥ 12 GB → alles auf GPU. Zwei Konfigprofile in `application.yml` / `.env` dokumentieren.
- Python-venv für `voice-service` auf dem Server, CUDA-fähiges CTranslate2 installieren, Piper-Stimmen herunterladen.
- Spring-Boot-Projekt ist generiert (`jarvis/`). Nacharbeit: groupId → `de.grauk`, `JarvisApplication` anlegen, Test-Paket verschieben, `application.properties` → `application.yml`, Azure-Vector-Store-Starter entfernen, `spring-ai-starter-model-anthropic` ergänzen.
- `README.md` mit Schritt-für-Schritt-Setup, `start-all.ps1`.
- **Verifikation:** vom PC aus `curl http://<llm-server>:11434/api/tags` zeigt Modelle, von einem anderen Rechner aus nicht; Spring Boot startet leer auf `127.0.0.1:8080`.

### Phase 1 – Text-Chat-MVP
- `AssistantService` mit `ChatClient` (Ollama), System-Prompt, `MessageChatMemoryAdvisor`.
- `Conversation`/`Message`-Entities, Flyway-Migration.
- Chat-Seite: Konversationsliste, Eingabe, SSE-Token-Streaming (`chat.js` + `EventSource`).
- **Verifikation:** Frage auf Deutsch und Englisch tippen, gestreamte Antwort erscheint, Verlauf bleibt nach Neustart erhalten, Folgefrage nutzt Kontext.

### Phase 1b – Umschaltbarer Chat-Provider (Ollama ↔ Claude)
- `ChatProviderRegistry` mit je einem `ChatClient` für Ollama und Anthropic; `provider` in `assistant_settings` und `message`.
- API-Key nur über `ANTHROPIC_API_KEY`; kein automatischer Fallback.
- **Verifikation:** Provider in den Settings auf Claude stellen → nächste Antwort kommt von Claude (im Message-Datensatz sichtbar, „☁ Cloud“-Badge); ohne Key ist Claude ausgegraut; LLM-Server aus → verständlicher Fehler statt stillem Wechsel.

### Phase 2 – Sprache rein (STT)
- `voice-service`: `/transcribe` (faster-whisper, `vad_filter=True`, Sprache auto), `/health`.
- Spring: `VoiceServiceClient` (RestClient), `POST /api/voice/transcribe`.
- Browser: Push-to-Talk (Button + Leertaste), MediaRecorder → Upload → erkannter Text ins Eingabefeld → automatisch abschicken.
- **Verifikation:** deutscher und englischer Satz sprechen → Text korrekt, Sprache korrekt erkannt, Ende-zu-Ende-Latenz STT gemessen und im Message-Datensatz gespeichert; pytest für `/transcribe` mit Beispiel-WAV.

### Phase 3 – Sprache raus (TTS)
- `voice-service`: `/synthesize` mit Piper (Stimme nach `language`).
- Spring: `VoiceSelector` (lingua-Spracherkennung auf Antworttext), `POST /api/voice/synthesize`.
- Browser: nach Stream-Ende WAV laden und abspielen; „Stopp“-Button; TTS-Toggle.
- **Verifikation:** gesprochene Frage → gesprochene Antwort in passender Sprache; Latenzen STT/LLM/TTS in DB und Status-Seite.

### Phase 4 – Dashboard: Einstellungen & Status
- `AssistantSettings`-Entity + Settings-Seite (Modell-Dropdown live aus Ollama, Prompt, Temperatur, Thinking-Modus, Stimmen).
- Provider- und Modellwechsel wirkt pro Request (`OllamaChatOptions` / `AnthropicChatOptions`), kein Neustart.
- `OllamaHealthIndicator`, `VoiceHealthIndicator`, Micrometer-Timer um STT/LLM/TTS, OSHI-Metriken, Log-Ringpuffer-Appender.
- Status-Seite mit HTMX-Polling (alle 5 s).
- **Verifikation:** voice-service stoppen → Ampel rot, Chat meldet verständlichen Fehler; Modell wechseln → nächste Antwort nutzt es (im Message-Datensatz sichtbar).

### Phase 5 – Tools
- `ToolRegistry`: sammelt alle `@Tool`-Beans, liest `tool_setting`, gibt nur aktivierte an den `ChatClient`.
- Erste Tools (Tabelle oben); `openApplication` mit Allowlist und Bestätigungsdialog (SSE-Event → Dashboard-Modal → Freigabe → Ausführung).
- `tool_call`-Protokoll, Tool-Badges im Chat, Tools-Seite.
- **Verifikation:** „Stell einen Timer auf 2 Minuten“ → Timer läuft, Ansage nach Ablauf; „Wie ist das Wetter in Berlin?“ → Open-Meteo-Aufruf; deaktiviertes Tool wird nicht aufgerufen.

### Phase 6 – Wissen & Gedächtnis
- `DocumentIngestionService` (Tika → Splitter → Embedding → `SimpleVectorStore`, Persistenz als JSON-Datei).
- `QuestionAnswerAdvisor` mit Similarity-Threshold, damit irrelevanter Kontext nicht injiziert wird.
- `rememberFact`-Tool + Fakten-Liste im Dashboard; Fakten werden ebenfalls in den VectorStore eingebettet.
- Wissen-Seite mit Upload, Index-Status, Fakten löschen.
- **Verifikation:** PDF hochladen → Frage zum Inhalt korrekt beantwortet; „Merk dir, dass mein Auto blau ist“ → neue Konversation, „Welche Farbe hat mein Auto?“ → korrekt.

### Phase 7 – Feinschliff (optional, nach Bedarf)
- Satzweises TTS während des Streamings (geringere gefühlte Latenz).
- Always-on-Listener mit VAD im Sidecar oder globaler Hotkey (dann ohne offenen Browser nutzbar).
- Autostart der Dienste beim Windows-Login (Aufgabenplanung).
- Export/Backup von Konversationen, Dark-Mode, Konversations-Suche.

## Backend – KI-Kern (Brenner)

### Verantwortung

Alles zwischen „Text kommt rein“ und „Antwort-Stream geht raus“: Provider-Wahl (Ollama/Claude),
Prompt-Aufbau, Chat-Verlauf, RAG über Dokumente und Fakten, Tools inkl. Bestätigungspflicht.
Nicht enthalten: HTTP/SSE-Endpoints, Persistenz von Konversationen/Nachrichten, Settings- und
Status-Seiten (Hiebler) sowie Audio (voice/, voice-service). Details, Signaturen und Konfiguration:
[`docs/CODE.md`](docs/CODE.md).

### Pakete und Klassen

| Paket | Klassen | Zweck |
|---|---|---|
| `assistant/` | `AssistantService`, `AssistantEvent`, `ChatProvider`, `ChatProviderRegistry`, `ChatClientConfig`, `PromptTemplates`, `ProviderUnavailableException` | Chat-Pipeline, Provider-Umschaltung, Event-Stream |
| `knowledge/` | `DocumentIngestionService`, `KnowledgeDocument`, `FactMemory`, `MemoryFact`, `VectorStoreConfig`, Repositories | Dokumente und Fakten einbetten, `SimpleVectorStore` |
| `tools/` | `ToolRegistry`, `ToolSetting`, `ConfirmationGate`, `builtin/*Tools` | Tools bereitstellen, filtern, Bestätigung |

### Arbeitspakete

| Phase | Arbeitspaket | Verifikation |
|---|---|---|
| 0 | `pom.xml` bereinigen (Azure raus, Anthropic rein), `JarvisApplication`, `application.yml` mit `spring.ai.*`/`jarvis.*`-Keys | `mvn verify` grün, App startet ohne Azure-Konfiguration |
| 1 | `ChatClientConfig` (Ollama), `AssistantService.stream` mit System-Prompt + `MessageChatMemoryAdvisor`, `AssistantEvent`-Vertrag mit Hiebler abstimmen | Unit-Test mit gemocktem `ChatModel`; Folgefrage nutzt Kontext |
| 1b | `ChatProviderRegistry`, Anthropic-`ChatClient`, Optionen je Provider, `ProviderUnavailableException` | Provider-Wechsel wirkt ohne Neustart; kein stiller Fallback |
| 5 | `ToolRegistry`, `ToolSetting`, `ConfirmationGate`, die sieben Tools aus der Tabelle oben, `TimerExpiredEvent` | deaktiviertes Tool wird nicht angeboten; `openApplication` läuft erst nach Freigabe, sonst Timeout; Tools funktionieren mit beiden Providern |
| 6 | `VectorStoreConfig`, `DocumentIngestionService`, `FactMemory` + `MemoryFact`, `QuestionAnswerAdvisor` mit Schwellwert | PDF-Frage korrekt beantwortet; Fakt aus alter Konversation wird in neuer gefunden – auch mit Claude |

### Schnittstellen

- **Hiebler (`web/`, `conversation/`):** ruft `AssistantService.stream(conversationId, text)` auf und streamt die `AssistantEvent`s als SSE (`token`, `tool`, `confirm`, `warning`, `done`, `error`). Persistiert Nachricht + Tool-Aufrufe aus `Done`.
- **Hiebler (`settings/`, `status/`):** liefert `SettingsService.current()` (inkl. `provider`); liest `ChatProviderRegistry.available()` / `models()`.
- **Frontend (Zheng, Zugaj):** Bestätigungsdialog auf `confirm` → `POST /api/tools/confirmations/{id}`; „☁ Cloud“-Badge; Timer-Ansage.
- **Server (Kreiter):** Ollama unter `JARVIS_LLM_URL` erreichbar, Firewall nur für den Jarvis-PC.

## Risiken & offene Punkte

- **VRAM-Budget auf dem LLM-Server bei 8 GB:** qwen3:8b (~5 GB) + Whisper turbo (~1–1,5 GB) + bge-m3 (~1,2 GB) ist knapp; Ollama lagert Modelle bei Bedarf aus → Latenzspitzen. Wird in Phase 0 gemessen und über Profile gelöst.
- **LLM-Server nicht erreichbar:** Chat und Wissen fallen aus (auch im Claude-Modus fehlt dann RAG, da Embeddings immer von Ollama kommen). Wird im Chat als Fehler bzw. Warnung angezeigt, nie still verschluckt.
- **Cloud-Modus:** Chat-Verlauf, Dokument-Chunks und Fakten gehen an Anthropic, es entstehen API-Kosten. Nur bewusst per Settings aktivierbar; ein „Privat“-Flag für Dokumente/Fakten ist eine mögliche Erweiterung.
- **Erster Request nach Leerlauf:** Ollama entlädt Modelle nach 5 min → `keep_alive` hochsetzen (z. B. `-1` oder `30m`).
- **Thinking-Modus von qwen3** erhöht Latenz deutlich → standardmäßig aus, per Setting einschaltbar.
- **Tool-Calling-Qualität** hängt vom Modell ab; Fallback `llama3.1:8b` ist dokumentiert.
- **Piper-Projektstatus:** Original-Repo archiviert, Nachfolger `piper1-gpl` (PyPI `piper-tts`) – beim Setup prüfen, dass Stimmen-Downloads funktionieren.
- **Browser-Audioformat:** Edge/Chrome liefern webm/opus, faster-whisper dekodiert das via PyAV ohne ffmpeg-Binary. Firefox liefert ogg/opus – ebenfalls ok.
- **Versionen:** Das Projekt nutzt Spring Boot 4.1.1 / Spring AI 2.0.1. Viele Beispiele im Netz beziehen sich auf Boot 3 / Spring AI 1.x – Klassen- und Property-Namen (z. B. `OllamaOptions` → `OllamaChatOptions`) beim Umsetzen gegen die tatsächliche Version prüfen.

## Nach Freigabe

Umsetzung beginnt mit Phase 0 und 1 (Skelett + Text-Chat), da beide keine Audio-Hardware brauchen und die Basis für alles Weitere sind. Jede Phase endet mit dem beschriebenen Verifikationsschritt, bevor die nächste startet.
