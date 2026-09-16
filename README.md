# Jarvis – Lokaler Sprachassistent mit Spring Boot, Ollama und Whisper

## Kontext

Ziel ist ein „Jarvis“-artiger Assistent, der komplett lokal auf dem Windows-Rechner läuft:
Sprache rein → lokales LLM → Sprache raus, plus ein Web-Dashboard, das den Assistenten
verwaltet (Chat-Verlauf, Modell/Prompt, Systemstatus, Tools, Wissen). Die Spring-Boot-
Anwendung ist das Herzstück: sie orchestriert alle Dienste und liefert das Dashboard.
Das Verzeichnis `GR_Auk` ist leer – Greenfield.

## Einteilung

| Name | Rolle |
|---|---|
| Kreiter | Server Aufbau |
| Brenner, Hiebler | Backend |
| Zheng, Zugaj | Frontend |
| Steinwidder | TTS |
| Radaelli | STT |

## Getroffene Entscheidungen

| Thema | Entscheidung | Begründung |
|---|---|---|
| LLM-Runtime | **Ollama** | REST-API, nativer Spring-AI-Support, Modelle per `ollama pull` wechselbar |
| Chat-Modell (Start) | **`qwen3:8b`** (Fallback `llama3.1:8b`) | DE+EN stark, Tool-Calling in Ollama unterstützt, passt in ≥ 8 GB VRAM quantisiert. Beim Setup prüfen, ob ein aktuellerer Nachfolger in der Ollama-Library liegt |
| Embedding-Modell | **`bge-m3`** | mehrsprachig (DE/EN), für RAG und Fakten-Gedächtnis |
| STT | **faster-whisper** (`large-v3-turbo`, CUDA) | beste Qualität für Deutsch, auto-Spracherkennung, läuft offline |
| TTS | **Piper** (piper1-gpl) | lokale Stimmen `de_DE-thorsten`, `en_US-lessac`; CPU reicht |
| STT+TTS-Hosting | **ein Python-Sidecar `voice-service`** (FastAPI) | Audio/ML-Ökosystem ist Python; Spring Boot bleibt reines Java und spricht nur HTTP |
| Frontend | **Thymeleaf + HTMX + kleines Vanilla-JS-Modul** | ein Projekt, kein Node-Build; JS nur für Mikrofon (MediaRecorder), SSE-Streaming und Audio-Playback |
| Styling | Bootstrap 5 via WebJars | offline-fähig, kein CDN |
| DB | **H2 im File-Modus** + Flyway | null Konfiguration, H2-Konsole zum Debuggen; SQLite/Postgres später möglich |
| Vector-Store | Spring AI `SimpleVectorStore` (JSON-Datei) | reicht für einige hundert Chunks; Upgrade-Pfad: pgvector/Qdrant |
| Build | Java 21, Maven, Spring Boot 3.x + Spring AI 1.x | Versionen beim Aufsetzen über start.spring.io auf den aktuellen Stand prüfen |
| Audio-Eingabe (MVP) | Push-to-Talk im Browser | einfachster Weg; Wake-Word wurde nicht gewünscht; Always-on-Listener als spätere Erweiterung |
| Netzwerk | nur `127.0.0.1`, keine Auth im MVP | rein lokal; Basic-Auth optional, falls später im LAN erreichbar |

## Zielarchitektur

```
 Browser (Dashboard, Thymeleaf/HTMX/JS)
   │  Mikrofon-Blob (webm/opus)  │ SSE (Token-Stream, Events)  │ WAV-Playback
   ▼                             ▼                             ▲
 ┌────────────────────────── jarvis-server (Spring Boot, :8080) ──────────────────────────┐
 │  web/        Thymeleaf-Seiten + HTMX-Fragmente + REST/SSE-Endpoints                    │
 │  assistant/  AssistantService: ChatClient + Advisors (Memory, RAG) + Tools             │
 │  voice/      VoiceServiceClient (HTTP → voice-service), Sprach-/Stimmenwahl            │
 │  tools/      @Tool-Beans + ToolRegistry (an/aus, Bestätigungspflicht)                  │
 │  knowledge/  Dokument-Ingestion, VectorStore, Fakten-Gedächtnis                        │
 │  settings/   Modell, System-Prompt, Temperatur, Stimmen (persistiert)                  │
 │  status/     HealthIndicators, Latenz-Metriken, Log-Ringpuffer                          │
 │  H2 (File) ── Konversationen, Nachrichten, Settings, Tool-Flags, Dokumente, Fakten     │
 └───────────────┬───────────────────────────────────────┬────────────────────────────────┘
                 │ Spring AI (HTTP)                      │ HTTP (multipart / JSON / WAV)
                 ▼                                       ▼
        Ollama (:11434)                         voice-service (Python/FastAPI, :8090)
        qwen3:8b  · bge-m3                      POST /transcribe  (faster-whisper, CUDA)
                                                POST /synthesize  (Piper)
                                                GET  /health      (Modelle geladen, GPU via pynvml)
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
GR_Auk/
├── README.md                  Setup-Anleitung (Ollama, Modelle, Python-Env, Start)
├── start-all.ps1              startet voice-service + jarvis-server (Ollama läuft als Windows-Dienst)
├── jarvis-server/             Spring Boot (Maven)
│   ├── pom.xml
│   └── src/main/java/de/grauk/jarvis/
│       ├── assistant/         AssistantService, ChatClientConfig, PromptTemplates
│       ├── conversation/      Conversation, Message (JPA), Repositories
│       ├── voice/             VoiceServiceClient, VoiceController, VoiceSelector
│       ├── tools/             ToolRegistry, ToolSetting (JPA), tools/*.java (@Tool)
│       ├── knowledge/         DocumentIngestionService, KnowledgeDocument, FactMemory
│       ├── settings/          AssistantSettings (JPA), SettingsService
│       ├── status/            OllamaHealthIndicator, VoiceHealthIndicator, LogBufferAppender, MetricsService
│       └── web/               DashboardController, ChatController (SSE), HTMX-Fragment-Controller
│   └── src/main/resources/
│       ├── application.yml    Ollama-URL, Modelle, voice-service-URL, H2-Pfad, server.address=127.0.0.1
│       ├── db/migration/      Flyway V1__init.sql …
│       ├── templates/         layout.html, chat.html, settings.html, status.html, tools.html, knowledge.html, fragments/
│       └── static/js/         chat.js (Recorder, SSE, Playback)
└── voice-service/             Python 3.11+, FastAPI
    ├── pyproject.toml / requirements.txt   fastapi, uvicorn, faster-whisper, piper-tts, pynvml
    ├── app/main.py            Endpoints /transcribe, /synthesize, /health
    ├── app/stt.py             WhisperModel (large-v3-turbo, device=cuda, compute_type=int8_float16, vad_filter)
    ├── app/tts.py             Piper-Voices laden, Text → WAV
    ├── models/                Piper-Stimmen (.onnx + .json), Whisper-Cache
    └── tests/                 pytest mit Beispiel-WAV
```

## Datenmodell (H2, Flyway)

- `conversation` (id, title, created_at, updated_at)
- `message` (id, conversation_id, role, content, language, model, stt_ms, llm_ms, tts_ms, created_at)
- `tool_call` (id, message_id, tool_name, arguments, result, created_at)
- `assistant_settings` (singleton: model, system_prompt, temperature, voice_de, voice_en, tts_enabled, think_mode)
- `tool_setting` (tool_name, enabled, requires_confirmation)
- `knowledge_document` (id, filename, chunks, indexed_at, status)
- `memory_fact` (id, content, source_message_id, created_at)
- Spring-AI-Chat-Memory-Tabelle über `spring-ai-starter-model-chat-memory-repository-jdbc`

## Dashboard-Seiten

| Seite | Inhalt |
|---|---|
| **Chat** | Konversationsliste, Nachrichtenverlauf, Texteingabe, Push-to-Talk-Button, Token-Streaming, Tool-Aufruf-Badges, Audio-Playback, erkannte Sprache |
| **Einstellungen** | Modell-Dropdown (live aus Ollama `/api/tags`), System-Prompt-Editor, Temperatur, Thinking-Modus an/aus, Stimmen DE/EN, TTS an/aus |
| **Status** | Ampel Ollama / voice-service / DB, geladene Modelle, GPU-VRAM & Auslastung (aus `/health` des Sidecars via pynvml), CPU/RAM (OSHI), letzte Latenzen STT/LLM/TTS, Log-Ringpuffer (letzte 200 Zeilen, HTMX-Polling) |
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
- Ollama installieren, `ollama pull qwen3:8b` und `bge-m3`; VRAM mit `nvidia-smi` prüfen.
- **VRAM-Kalibrierung:** bei genau 8 GB → Whisper `int8` oder `medium`, ggf. `qwen3:4b`; bei ≥ 12 GB → alles auf GPU. Zwei Konfigprofile in `application.yml` / `.env` dokumentieren.
- Python-venv für `voice-service`, CUDA-fähiges CTranslate2 installieren, Piper-Stimmen herunterladen.
- Spring-Boot-Projekt über start.spring.io generieren (Web, Thymeleaf, JPA, H2, Flyway, Actuator, Validation, Spring AI Ollama, Chat-Memory-JDBC, Tika-Reader, Vector-Store).
- `README.md` mit Schritt-für-Schritt-Setup, `start-all.ps1`.
- **Verifikation:** `curl localhost:11434/api/tags` zeigt Modelle; Spring Boot startet leer auf `127.0.0.1:8080`.

### Phase 1 – Text-Chat-MVP
- `AssistantService` mit `ChatClient` (Ollama), System-Prompt, `MessageChatMemoryAdvisor`.
- `Conversation`/`Message`-Entities, Flyway-Migration.
- Chat-Seite: Konversationsliste, Eingabe, SSE-Token-Streaming (`chat.js` + `EventSource`).
- **Verifikation:** Frage auf Deutsch und Englisch tippen, gestreamte Antwort erscheint, Verlauf bleibt nach Neustart erhalten, Folgefrage nutzt Kontext.

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
- Modellwechsel wirkt per `OllamaOptions` pro Request, kein Neustart.
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

## Risiken & offene Punkte

- **VRAM-Budget bei 8 GB:** qwen3:8b (~5 GB) + Whisper turbo (~1–1,5 GB) + bge-m3 (~1,2 GB) ist knapp; Ollama lagert Modelle bei Bedarf aus → Latenzspitzen. Wird in Phase 0 gemessen und über Profile gelöst.
- **Erster Request nach Leerlauf:** Ollama entlädt Modelle nach 5 min → `keep_alive` hochsetzen (z. B. `-1` oder `30m`).
- **Thinking-Modus von qwen3** erhöht Latenz deutlich → standardmäßig aus, per Setting einschaltbar.
- **Tool-Calling-Qualität** hängt vom Modell ab; Fallback `llama3.1:8b` ist dokumentiert.
- **Piper-Projektstatus:** Original-Repo archiviert, Nachfolger `piper1-gpl` (PyPI `piper-tts`) – beim Setup prüfen, dass Stimmen-Downloads funktionieren.
- **Browser-Audioformat:** Edge/Chrome liefern webm/opus, faster-whisper dekodiert das via PyAV ohne ffmpeg-Binary. Firefox liefert ogg/opus – ebenfalls ok.
- **Versionen:** Spring Boot / Spring AI Artefakt-Namen haben sich zwischen Milestones geändert; beim Generieren des Projekts die aktuellen Koordinaten aus start.spring.io übernehmen.

## Nach Freigabe

Umsetzung beginnt mit Phase 0 und 1 (Skelett + Text-Chat), da beide keine Audio-Hardware brauchen und die Basis für alles Weitere sind. Jede Phase endet mit dem beschriebenen Verifikationsschritt, bevor die nächste startet.
