# CONTEXT – Fachbegriffe von Jarvis

Gemeinsames Vokabular für Code, Doku und Gespräche. Wer einen neuen Begriff einführt oder einen
bestehenden anders verwendet, passt ihn hier an. Entscheidungen stehen in [`docs/adr/`](docs/adr/),
die Code-Doku in [`docs/CODE.md`](docs/CODE.md).

## Kern

| Begriff | Bedeutung | Im Code |
|---|---|---|
| **Jarvis** | Die Spring-Boot-Anwendung auf dem Arbeitsplatz-PC. Orchestriert alle Dienste und liefert das Dashboard. | Modul `jarvis/`, Paket `de.grauk.jarvis` |
| **LLM-Server** | Separater GPU-Rechner im LAN, auf dem Ollama und der voice-service laufen. | `spring.ai.ollama.base-url`, `jarvis.voice.base-url` |
| **voice-service** | Python-Sidecar (FastAPI) für STT (faster-whisper) und TTS (Piper). | `voice/VoiceServiceClient` |
| **Dashboard** | Die Weboberfläche (Chat, Einstellungen, Status, Tools, Wissen). | `web/`, `templates/` |

## Gespräch

| Begriff | Bedeutung | Im Code |
|---|---|---|
| **Konversation** | Ein Gesprächsfaden mit Titel; enthält Nachrichten. Grenze des Chat-Verlaufs. | `Conversation`, Tabelle `conversation` |
| **Nachricht** | Eine Äußerung mit Rolle `USER` oder `ASSISTANT`, inkl. Sprache, Provider, Modell und Latenzen. | `Message`, Tabelle `message` |
| **Chat-Verlauf (Memory)** | Die letzten Nachrichten einer Konversation, die dem Modell mitgegeben werden. Nicht zu verwechseln mit dem Gedächtnis. | `MessageChatMemoryAdvisor`, JDBC-Chat-Memory |
| **Anfrage** | Ein Durchlauf der Chat-Pipeline: Text rein → Token-Stream raus. Text- und Sprachweg teilen dieselbe Anfrage. | `AssistantService.stream(...)` |
| **Assistant-Event** | Ein Ereignis während einer Anfrage (Token, Tool aufgerufen, Bestätigung nötig, Fertig, Fehler); wird als SSE ans Dashboard gestreamt. | `AssistantEvent` |

## Modelle

| Begriff | Bedeutung | Im Code |
|---|---|---|
| **Provider** | Wer das Chat-Modell ausführt: `OLLAMA` (LLM-Server) oder `ANTHROPIC` (Claude, Cloud). Wird pro Anfrage aus den Einstellungen gelesen. | `ChatProvider`, `ChatProviderRegistry` |
| **Chat-Modell** | Das konkrete Modell eines Providers, z. B. `qwen3:8b` oder `claude-sonnet-5-5`. | `assistant_settings.model` |
| **Embedding-Modell** | Immer `bge-m3` auf Ollama, unabhängig vom Provider (ADR 0003). | `EmbeddingModel` |
| **Cloud-Modus** | Provider = `ANTHROPIC`. Daten verlassen den Rechner; im Chat als „☁ Cloud“ markiert. Nie automatisch aktiv (ADR 0005). | – |
| **Thinking-Modus** | Modell „denkt“ vor der Antwort (qwen3 `think`, Claude Extended Thinking). Standard aus. | `assistant_settings.think_mode` |

## Wissen & Gedächtnis

| Begriff | Bedeutung | Im Code |
|---|---|---|
| **Wissen** | Hochgeladene Dokumente, in Chunks zerlegt und eingebettet. Wird per RAG automatisch zur Anfrage hinzugezogen. | `DocumentIngestionService`, `KnowledgeDocument` |
| **Chunk** | Ein Textabschnitt eines Dokuments im VectorStore, mit Metadaten `type=document`, `documentId`. | `Document` (Spring AI) |
| **Fakt** | Ein kurzer Satz über den Nutzer, den Jarvis sich merken soll („Mein Auto ist blau“). Liegt in `memory_fact` **und** im VectorStore (`type=fact`). | `FactMemory`, `MemoryFact` |
| **Gedächtnis** | Die Menge aller Fakten – überdauert Konversationen. Nicht zu verwechseln mit dem Chat-Verlauf. | `FactMemory` |
| **RAG-Kontext** | Chunks und Fakten, die per Ähnlichkeitssuche (mit Schwellwert) in den Prompt kommen. | `QuestionAnswerAdvisor` |
| **VectorStore** | `SimpleVectorStore`, persistiert als JSON-Datei (ADR 0004). | `VectorStoreConfig` |

## Tools

| Begriff | Bedeutung | Im Code |
|---|---|---|
| **Tool** | Eine Java-Methode mit `@Tool`, die das Modell aufrufen darf (Timer, Wetter, …). | `tools/builtin/*` |
| **Tool-Einstellung** | Pro Tool: aktiv ja/nein, Bestätigung nötig ja/nein. | `ToolSetting`, Tabelle `tool_setting` |
| **Bestätigungspflicht** | Das Tool läuft erst, nachdem der Nutzer im Dashboard zugestimmt hat; sonst Abbruch nach Timeout. | `ConfirmationGate` |
| **Tool-Aufruf** | Protokoll eines Aufrufs (Name, Argumente, Ergebnis) zu einer Nachricht. | Tabelle `tool_call` |
| **Allowlist** | Liste der Programme, die `openApplication` starten darf. | `jarvis.tools.open-application.allowlist` |
