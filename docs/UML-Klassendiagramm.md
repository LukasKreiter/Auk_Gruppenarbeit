# Jarvis – UML-Diagramme (Entwurf)

Entwurf der Klassen im Paket `de.grauk.jarvis` mit möglichen Attributen und Methoden.
Grundlage ist die Architektur aus der [README](../README.md). Die Klassen im Code sind noch leer;
Klassen mit dem Vermerk *(neu)* existieren noch nicht als Datei und werden vorgeschlagen.

**Legende:** `+` public, `-` private, `#` protected, `$` static, `*` abstract ·
`<<Entity>>` = JPA-Entity (H2-Tabelle) · `<<record>>` = Java-Record (DTO) ·
Klassen ohne Inhalt in einem Package-Diagramm stammen aus einem **anderen Package** bzw. aus Spring/Bibliotheken.

**Inhalt**

1. [Package-Übersicht](#1-package-übersicht)
2. [Datenmodell](#2-datenmodell-alle-entities)
3. [web](#3-package-web)
4. [assistant](#4-package-assistant)
5. [conversation](#5-package-conversation)
6. [voice](#6-package-voice)
7. [tools](#7-package-tools)
8. [knowledge](#8-package-knowledge)
9. [settings](#9-package-settings)
10. [status](#10-package-status)

---

## 1. Package-Übersicht

Nur die Abhängigkeiten zwischen den Packages (Pfeil = „verwendet“) und zu den externen Systemen.

```mermaid
flowchart TB
    web["📦 web<br/><small>Seiten, REST/SSE, HTMX-Fragmente</small>"]
    assistant["📦 assistant<br/><small>ChatClient, Prompt, Streaming</small>"]
    conversation["📦 conversation<br/><small>Konversationen, Nachrichten, Tool-Aufrufe</small>"]
    voice["📦 voice<br/><small>STT/TTS über voice-service</small>"]
    tools["📦 tools<br/><small>@Tool-Beans, Registry</small>"]
    knowledge["📦 knowledge<br/><small>RAG, VectorStore, Fakten</small>"]
    settings["📦 settings<br/><small>Modell, Prompt, Stimmen</small>"]
    status["📦 status<br/><small>Health, Metriken, Logs</small>"]

    ollama[("Ollama :11434")]
    voicesvc[("voice-service :8090")]
    h2[("H2-Datenbank")]

    web --> assistant
    web --> conversation
    web --> settings
    web --> tools
    web --> knowledge
    web --> status

    assistant --> conversation
    assistant --> settings
    assistant --> tools
    assistant --> knowledge
    assistant --> status

    voice --> settings
    voice --> status

    tools --> knowledge
    tools --> status
    tools --> conversation

    status --> voice

    assistant -. Spring AI .-> ollama
    knowledge -. Embeddings .-> ollama
    settings -. /api/tags .-> ollama
    status -. Health .-> ollama
    voice -. HTTP .-> voicesvc

    conversation -. JPA .-> h2
    settings -. JPA .-> h2
    tools -. JPA .-> h2
    knowledge -. JPA .-> h2
```

| Package | verwendet | Grund |
|---|---|---|
| `web` | assistant, conversation, settings, tools, knowledge, status | Controller für alle Dashboard-Seiten |
| `assistant` | conversation, settings, tools, knowledge, status | Verlauf speichern, Settings lesen, aktive Tools, RAG, Latenz messen |
| `voice` | settings, status | Stimmenwahl, STT/TTS-Latenzen |
| `tools` | knowledge, status, conversation | `rememberFact`/`searchKnowledge`, `getSystemStatus`, letzte Aufrufe |
| `status` | voice | `VoiceHealthIndicator` fragt den voice-service ab |
| `knowledge`, `settings`, `conversation` | – | Basis-Packages ohne interne Abhängigkeiten |

---

## 2. Datenmodell (alle Entities)

Package-übergreifende Sicht auf die Tabellen in H2.

```mermaid
classDiagram
    direction LR

    class Conversation {
        <<Entity>>
        -id : Long
        -title : String
        -createdAt : LocalDateTime
        -updatedAt : LocalDateTime
    }
    class Message {
        <<Entity>>
        -id : Long
        -role : Role
        -content : String
        -language : String
        -model : String
        -sttMs : Long
        -llmMs : Long
        -ttsMs : Long
        -createdAt : LocalDateTime
    }
    class ToolCall {
        <<Entity>>
        -id : Long
        -toolName : String
        -arguments : String
        -result : String
        -status : ToolCallStatus
        -createdAt : LocalDateTime
    }
    class AssistantSettings {
        <<Entity>>
        -id : Long
        -model : String
        -systemPrompt : String
        -temperature : double
        -voiceDe : String
        -voiceEn : String
        -ttsEnabled : boolean
        -thinkMode : boolean
    }
    class ToolSetting {
        <<Entity>>
        -toolName : String
        -description : String
        -enabled : boolean
        -requiresConfirmation : boolean
    }
    class KnowledgeDocument {
        <<Entity>>
        -id : Long
        -filename : String
        -chunks : int
        -indexedAt : LocalDateTime
        -status : IndexStatus
    }
    class MemoryFact {
        <<Entity>>
        -id : Long
        -content : String
        -sourceMessageId : Long
        -createdAt : LocalDateTime
    }

    Conversation "1" *-- "0..*" Message
    Message "1" *-- "0..*" ToolCall
    ToolSetting "1" .. "0..*" ToolCall : toolName
    MemoryFact "0..*" ..> "0..1" Message : sourceMessageId
```

---

## 3. Package `web`

Liefert die Thymeleaf-Seiten, die REST/SSE-Endpoints für den Chat und die HTMX-Fragmente.

```mermaid
classDiagram
    direction LR

    class DashboardController {
        <<Controller>>
        -conversationRepository : ConversationRepository
        -settingsService : SettingsService
        -toolRegistry : ToolRegistry
        -ingestionService : DocumentIngestionService
        -factMemory : FactMemory
        +index() String
        +chat(conversationId: Long, model: Model) String
        +settings(model: Model) String
        +status(model: Model) String
        +tools(model: Model) String
        +knowledge(model: Model) String
    }

    class ChatController {
        <<RestController>>
        -assistantService : AssistantService
        -conversationRepository : ConversationRepository
        -messageRepository : MessageRepository
        -toolRegistry : ToolRegistry
        +listConversations() List~Conversation~
        +createConversation() Conversation
        +renameConversation(id: Long, title: String) Conversation
        +deleteConversation(id: Long) ResponseEntity~Void~
        +getMessages(id: Long) List~Message~
        +sendMessage(id: Long, request: ChatRequest) SseEmitter
        +confirmToolCall(callId: Long, request: ToolConfirmation) ResponseEntity~Void~
        -toSseEvent(event: ChatEvent) SseEventBuilder
    }

    class HTMXFragmentController {
        <<Controller>>
        -conversationRepository : ConversationRepository
        -settingsService : SettingsService
        -toolRegistry : ToolRegistry
        -metricsService : MetricsService
        -ingestionService : DocumentIngestionService
        -factMemory : FactMemory
        +conversationList(model: Model) String
        +statusPanel(model: Model) String
        +logPanel(model: Model) String
        +saveSettings(form: AssistantSettings, model: Model) String
        +toggleTool(name: String, enabled: boolean, model: Model) String
        +toggleConfirmation(name: String, value: boolean, model: Model) String
        +uploadDocument(file: MultipartFile, model: Model) String
        +documentList(model: Model) String
        +factList(model: Model) String
        +deleteFact(id: Long, model: Model) String
    }

    class ChatRequest {
        <<record>>
        +text : String
        +language : String
        +sttMs : Long
    }

    class ToolConfirmation {
        <<record>>
        +approved : boolean
    }

    class GlobalExceptionHandler {
        <<ControllerAdvice>>
        +handleVoiceError(ex: VoiceServiceException) ResponseEntity~ErrorResponse~
        +handleNotFound(ex: EntityNotFoundException) ResponseEntity~ErrorResponse~
    }

    %% Klassen aus anderen Packages
    class AssistantService
    class ChatEvent
    class ConversationRepository
    class MessageRepository
    class SettingsService
    class ToolRegistry
    class MetricsService
    class DocumentIngestionService
    class FactMemory

    ChatController ..> ChatRequest
    ChatController ..> ToolConfirmation
    ChatController --> AssistantService
    ChatController ..> ChatEvent
    ChatController --> ConversationRepository
    ChatController --> MessageRepository
    ChatController --> ToolRegistry

    DashboardController --> ConversationRepository
    DashboardController --> SettingsService
    DashboardController --> ToolRegistry
    DashboardController --> DocumentIngestionService
    DashboardController --> FactMemory

    HTMXFragmentController --> SettingsService
    HTMXFragmentController --> ToolRegistry
    HTMXFragmentController --> MetricsService
    HTMXFragmentController --> DocumentIngestionService
    HTMXFragmentController --> FactMemory

    note for DashboardController "GET / · /chat/{id} · /settings · /status · /tools · /knowledge"
    note for ChatController "/api/conversations/** (JSON + SSE)"
    note for HTMXFragmentController "/fragments/** (liefert HTML-Schnipsel für HTMX)"
```

*(neu)*: `ChatRequest`, `ToolConfirmation`, `GlobalExceptionHandler`

---

## 4. Package `assistant`

Herzstück: baut den Prompt (System-Prompt + Verlauf + RAG + Tools) und streamt die Antwort von Ollama.

```mermaid
classDiagram
    direction LR

    class ChatClientConfig {
        <<Configuration>>
        +chatMemory(repository: JdbcChatMemoryRepository) ChatMemory
        +chatClient(builder: ChatClient.Builder, chatMemory: ChatMemory, vectorStore: VectorStore) ChatClient
        -memoryAdvisor(chatMemory: ChatMemory) MessageChatMemoryAdvisor
        -ragAdvisor(vectorStore: VectorStore) QuestionAnswerAdvisor
    }

    class AssistantService {
        <<Service>>
        -chatClient : ChatClient
        -settingsService : SettingsService
        -toolRegistry : ToolRegistry
        -conversationRepository : ConversationRepository
        -messageRepository : MessageRepository
        -toolCallRepository : ToolCallRepository
        -metricsService : MetricsService
        +streamAnswer(conversationId: Long, userText: String, sttMs: Long) Flux~ChatEvent~
        +answer(conversationId: Long, userText: String) String
        +cancel(conversationId: Long) void
        -buildOptions(settings: AssistantSettings) OllamaOptions
        -saveUserMessage(conversation: Conversation, text: String, sttMs: Long) Message
        -saveAssistantMessage(conversation: Conversation, text: String, llmMs: long) Message
        -logToolCall(message: Message, toolName: String, args: String, result: String) ToolCall
        -generateTitleIfNeeded(conversation: Conversation, firstMessage: String) void
    }

    class PromptTemplates {
        <<utility>>
        +DEFAULT_SYSTEM_PROMPT : String$
        +RAG_TEMPLATE : String$
        +TITLE_TEMPLATE : String$
        -PromptTemplates()
        +systemPrompt(settings: AssistantSettings, now: LocalDateTime) String$
        +conversationTitle(firstMessage: String) String$
    }

    class ChatEvent {
        <<record>>
        +type : ChatEventType
        +data : String
        +token(text: String) ChatEvent$
        +toolCall(toolName: String) ChatEvent$
        +done(messageId: Long) ChatEvent$
        +error(message: String) ChatEvent$
    }

    class ChatEventType {
        <<enumeration>>
        TOKEN
        TOOL_CALL
        TOOL_CONFIRMATION
        DONE
        ERROR
    }

    %% Spring AI
    class ChatClient {
        <<interface>>
    }
    class ChatMemory {
        <<interface>>
    }

    %% Klassen aus anderen Packages
    class SettingsService
    class ToolRegistry
    class ConversationRepository
    class MessageRepository
    class ToolCallRepository
    class MetricsService
    class VectorStore

    ChatClientConfig ..> ChatClient : erzeugt
    ChatClientConfig ..> ChatMemory : erzeugt
    ChatClientConfig ..> VectorStore : RAG-Advisor
    AssistantService --> ChatClient
    AssistantService ..> PromptTemplates
    AssistantService ..> ChatEvent : liefert
    ChatEvent --> ChatEventType
    AssistantService --> SettingsService
    AssistantService --> ToolRegistry
    AssistantService --> ConversationRepository
    AssistantService --> MessageRepository
    AssistantService --> ToolCallRepository
    AssistantService --> MetricsService
```

*(neu)*: `ChatEvent`, `ChatEventType` (die Events werden als SSE an den Browser geschickt)

---

## 5. Package `conversation`

Persistenz von Konversationen, Nachrichten und Tool-Aufrufen.

```mermaid
classDiagram
    direction LR

    class Conversation {
        <<Entity>>
        -id : Long
        -title : String
        -createdAt : LocalDateTime
        -updatedAt : LocalDateTime
        -messages : List~Message~
        +Conversation(title: String)
        +addMessage(message: Message) void
        +getLastMessage() Message
        +getMessageCount() int
        #onCreate() void
        #onUpdate() void
    }

    class Message {
        <<Entity>>
        -id : Long
        -conversation : Conversation
        -role : Role
        -content : String
        -language : String
        -model : String
        -sttMs : Long
        -llmMs : Long
        -ttsMs : Long
        -createdAt : LocalDateTime
        -toolCalls : List~ToolCall~
        +Message(conversation: Conversation, role: Role, content: String)
        +addToolCall(toolCall: ToolCall) void
        +getTotalLatencyMs() long
    }

    class Role {
        <<enumeration>>
        USER
        ASSISTANT
        SYSTEM
        TOOL
    }

    class ToolCall {
        <<Entity>>
        -id : Long
        -message : Message
        -toolName : String
        -arguments : String
        -result : String
        -status : ToolCallStatus
        -createdAt : LocalDateTime
        +ToolCall(message: Message, toolName: String, arguments: String)
        +complete(result: String) void
        +reject() void
    }

    class ToolCallStatus {
        <<enumeration>>
        PENDING_CONFIRMATION
        EXECUTED
        REJECTED
        FAILED
    }

    class JpaRepository~T~ {
        <<interface>>
        +findById(id: Long) Optional~T~
        +findAll() List~T~
        +save(entity: T) T
        +deleteById(id: Long) void
    }

    class ConversationRepository {
        <<interface>>
        +findAllByOrderByUpdatedAtDesc() List~Conversation~
        +findByTitleContainingIgnoreCase(text: String) List~Conversation~
    }

    class MessageRepository {
        <<interface>>
        +findByConversationIdOrderByCreatedAt(conversationId: Long) List~Message~
        +findTopByRoleOrderByCreatedAtDesc(role: Role) Optional~Message~
        +countByConversationId(conversationId: Long) long
    }

    class ToolCallRepository {
        <<interface>>
        +findTop20ByToolNameOrderByCreatedAtDesc(toolName: String) List~ToolCall~
        +findByStatus(status: ToolCallStatus) List~ToolCall~
    }

    Conversation "1" *-- "0..*" Message : messages
    Message "1" *-- "0..*" ToolCall : toolCalls
    Message --> Role
    ToolCall --> ToolCallStatus

    JpaRepository <|-- ConversationRepository : T = Conversation
    JpaRepository <|-- MessageRepository : T = Message
    JpaRepository <|-- ToolCallRepository : T = ToolCall

    note for ConversationRepository "Alle drei Interfaces liegen in Repositories.java"
```

*(neu)*: `Role`, `ToolCall`, `ToolCallStatus`

---

## 6. Package `voice`

Brücke zum Python-Sidecar `voice-service` (faster-whisper + Piper).

```mermaid
classDiagram
    direction LR

    class VoiceController {
        <<RestController>>
        -voiceServiceClient : VoiceServiceClient
        -voiceSelector : VoiceSelector
        -settingsService : SettingsService
        -metricsService : MetricsService
        +transcribe(audio: MultipartFile) TranscriptionResult
        +synthesize(request: SynthesizeRequest) ResponseEntity~byte[]~
        +listVoices() List~String~
    }

    class VoiceServiceClient {
        <<Component>>
        -restClient : RestClient
        -baseUrl : String
        -timeout : Duration
        +transcribe(audio: byte[], contentType: String) TranscriptionResult
        +synthesize(text: String, voice: String) byte[]
        +health() VoiceHealth
        +isAvailable() boolean
    }

    class VoiceSelector {
        <<Component>>
        -languageDetector : LanguageDetector
        -settingsService : SettingsService
        +detectLanguage(text: String) String
        +selectVoice(text: String) String
        +voiceForLanguage(language: String) String
    }

    class TranscriptionResult {
        <<record>>
        +text : String
        +language : String
        +durationMs : long
    }

    class SynthesizeRequest {
        <<record>>
        +text : String
        +language : String
        +messageId : Long
    }

    class VoiceHealth {
        <<record>>
        +status : String
        +whisperLoaded : boolean
        +piperVoices : List~String~
        +gpuName : String
        +vramUsedMb : long
        +vramTotalMb : long
        +gpuUtilization : int
    }

    class VoiceServiceException {
        <<exception>>
        +VoiceServiceException(message: String, cause: Throwable)
    }

    %% Klassen aus anderen Packages / Bibliotheken
    class SettingsService
    class MetricsService
    class LanguageDetector
    class RuntimeException

    VoiceController --> VoiceServiceClient
    VoiceController --> VoiceSelector
    VoiceController --> SettingsService : ttsEnabled
    VoiceController --> MetricsService : STT/TTS-Zeit
    VoiceController ..> SynthesizeRequest
    VoiceServiceClient ..> TranscriptionResult : liefert
    VoiceServiceClient ..> VoiceHealth : liefert
    VoiceServiceClient ..> VoiceServiceException : wirft
    VoiceSelector --> LanguageDetector : lingua
    VoiceSelector --> SettingsService : voiceDe / voiceEn
    RuntimeException <|-- VoiceServiceException

    note for VoiceController "POST /api/voice/transcribe · POST /api/voice/synthesize"
```

*(neu)*: `TranscriptionResult`, `SynthesizeRequest`, `VoiceHealth`, `VoiceServiceException`

---

## 7. Package `tools`

Die Tools, die das LLM aufrufen darf, und ihre Verwaltung (an/aus, Bestätigungspflicht).

```mermaid
classDiagram
    direction TB

    class ToolRegistry {
        <<Component>>
        -tools : List~JarvisTool~
        -toolSettingRepository : ToolSettingRepository
        -toolCallRepository : ToolCallRepository
        +syncToolSettings() void
        +getAllTools() List~ToolSetting~
        +getEnabledTools() Object[]
        +isEnabled(toolName: String) boolean
        +setEnabled(toolName: String, enabled: boolean) void
        +requiresConfirmation(toolName: String) boolean
        +setRequiresConfirmation(toolName: String, value: boolean) void
        +getRecentCalls(toolName: String) List~ToolCall~
    }

    class ToolSetting {
        <<Entity>>
        -toolName : String
        -description : String
        -enabled : boolean
        -requiresConfirmation : boolean
        +ToolSetting(toolName: String, description: String)
        +toggle() void
    }

    class ToolSettingRepository {
        <<interface>>
        +findByEnabledTrue() List~ToolSetting~
        +findByToolName(toolName: String) Optional~ToolSetting~
    }

    class JarvisTool {
        <<interface>>
        +getName() String
        +getDescription() String
        +requiresConfirmationByDefault() boolean
    }

    class DateTimeTool {
        <<Component>>
        -clock : Clock
        +getCurrentDateTime() String
    }

    class TimerTool {
        <<Component>>
        -taskScheduler : TaskScheduler
        -eventPublisher : ApplicationEventPublisher
        -activeTimers : Map~String, ScheduledFuture~
        +setTimer(minutes: int, label: String) String
        +cancelTimer(label: String) String
        -onTimerExpired(label: String) void
    }

    class TimerExpiredEvent {
        <<record>>
        +label : String
        +minutes : int
    }

    class WeatherTool {
        <<Component>>
        -restClient : RestClient
        +getWeather(city: String) String
        -geocode(city: String) Coordinates
    }

    class MemoryTool {
        <<Component>>
        -factMemory : FactMemory
        +rememberFact(text: String) String
    }

    class KnowledgeTool {
        <<Component>>
        -ingestionService : DocumentIngestionService
        +searchKnowledge(query: String) String
    }

    class SystemTool {
        <<Component>>
        -allowedApps : Map~String, String~
        -metricsService : MetricsService
        +openApplication(name: String) String
        +getSystemStatus() String
    }

    %% Klassen aus anderen Packages
    class ToolCallRepository
    class FactMemory
    class DocumentIngestionService
    class MetricsService

    ToolRegistry "1" o-- "0..*" JarvisTool : sammelt
    ToolRegistry --> ToolSettingRepository
    ToolRegistry --> ToolCallRepository
    ToolSettingRepository ..> ToolSetting

    JarvisTool <|.. DateTimeTool
    JarvisTool <|.. TimerTool
    JarvisTool <|.. WeatherTool
    JarvisTool <|.. MemoryTool
    JarvisTool <|.. KnowledgeTool
    JarvisTool <|.. SystemTool

    TimerTool ..> TimerExpiredEvent : publiziert
    MemoryTool --> FactMemory
    KnowledgeTool --> DocumentIngestionService
    SystemTool --> MetricsService

    note for SystemTool "openApplication: nur Programme aus der Allowlist in application.yml"
    note for WeatherTool "Open-Meteo API (einziges Tool mit Internet)"
```

*(neu)*: `JarvisTool`, `DateTimeTool`, `TimerTool`, `TimerExpiredEvent`, `WeatherTool`, `MemoryTool`,
`KnowledgeTool`, `SystemTool`. Die Tool-Methoden tragen die Annotation `@Tool`.

---

## 8. Package `knowledge`

Dokument-Upload für RAG und das Langzeitgedächtnis (Fakten).

```mermaid
classDiagram
    direction LR

    class VectorStoreConfig {
        <<Configuration>>
        -storeFile : Path
        +vectorStore(embeddingModel: EmbeddingModel) SimpleVectorStore
        +textSplitter() TokenTextSplitter
    }

    class DocumentIngestionService {
        <<Service>>
        -vectorStore : SimpleVectorStore
        -documentRepository : KnowledgeDocumentRepository
        -textSplitter : TokenTextSplitter
        -uploadDir : Path
        +ingest(file: MultipartFile) KnowledgeDocument
        +reindex(documentId: Long) KnowledgeDocument
        +deleteDocument(documentId: Long) void
        +listDocuments() List~KnowledgeDocument~
        +search(query: String, topK: int) List~Document~
        -readWithTika(resource: Resource) List~Document~
        -persistVectorStore() void
    }

    class KnowledgeDocument {
        <<Entity>>
        -id : Long
        -filename : String
        -chunks : int
        -indexedAt : LocalDateTime
        -status : IndexStatus
        -errorMessage : String
        +KnowledgeDocument(filename: String)
        +markIndexing() void
        +markIndexed(chunks: int) void
        +markFailed(error: String) void
    }

    class IndexStatus {
        <<enumeration>>
        PENDING
        INDEXING
        INDEXED
        FAILED
    }

    class FactMemory {
        <<Service>>
        -factRepository : MemoryFactRepository
        -vectorStore : SimpleVectorStore
        +remember(content: String, sourceMessageId: Long) MemoryFact
        +listFacts() List~MemoryFact~
        +findRelevant(query: String, topK: int) List~MemoryFact~
        +forget(id: Long) void
    }

    class MemoryFact {
        <<Entity>>
        -id : Long
        -content : String
        -sourceMessageId : Long
        -vectorId : String
        -createdAt : LocalDateTime
        +MemoryFact(content: String, sourceMessageId: Long)
    }

    class KnowledgeDocumentRepository {
        <<interface>>
        +findAllByOrderByIndexedAtDesc() List~KnowledgeDocument~
        +findByStatus(status: IndexStatus) List~KnowledgeDocument~
    }

    class MemoryFactRepository {
        <<interface>>
        +findAllByOrderByCreatedAtDesc() List~MemoryFact~
    }

    %% Spring AI
    class VectorStore {
        <<interface>>
        +add(documents: List~Document~) void
        +delete(ids: List~String~) void
        +similaritySearch(request: SearchRequest) List~Document~
    }
    class SimpleVectorStore
    class EmbeddingModel {
        <<interface>>
    }

    VectorStoreConfig ..> SimpleVectorStore : erzeugt
    VectorStoreConfig ..> EmbeddingModel : bge-m3
    VectorStore <|.. SimpleVectorStore

    DocumentIngestionService --> SimpleVectorStore
    DocumentIngestionService --> KnowledgeDocumentRepository
    KnowledgeDocumentRepository ..> KnowledgeDocument
    KnowledgeDocument --> IndexStatus

    FactMemory --> SimpleVectorStore
    FactMemory --> MemoryFactRepository
    MemoryFactRepository ..> MemoryFact

    note for DocumentIngestionService "PDF/DOCX/MD/TXT → Tika → TokenTextSplitter → bge-m3 → VectorStore (JSON-Datei)"
```

*(neu)*: `VectorStoreConfig`, `IndexStatus`, `MemoryFact`, beide Repositories

---

## 9. Package `settings`

Persistente Einstellungen des Assistenten, die im Dashboard geändert werden können.

```mermaid
classDiagram
    direction LR

    class AssistantSettings {
        <<Entity>>
        +SINGLETON_ID : Long = 1$
        -id : Long
        -model : String
        -systemPrompt : String
        -temperature : double
        -voiceDe : String
        -voiceEn : String
        -ttsEnabled : boolean
        -thinkMode : boolean
        -updatedAt : LocalDateTime
        +defaults() AssistantSettings$
        +copyFrom(other: AssistantSettings) void
    }

    class AssistantSettingsRepository {
        <<interface>>
    }

    class SettingsService {
        <<Service>>
        -repository : AssistantSettingsRepository
        -ollamaApi : OllamaApi
        -cachedSettings : AssistantSettings
        +getSettings() AssistantSettings
        +update(settings: AssistantSettings) AssistantSettings
        +resetToDefaults() AssistantSettings
        +getAvailableModels() List~String~
        +getVoiceFor(language: String) String
        +isTtsEnabled() boolean
        -validate(settings: AssistantSettings) void
    }

    class OllamaApi {
        <<Spring AI>>
        +listModels() ListModelResponse
    }

    SettingsService --> AssistantSettingsRepository
    SettingsService --> OllamaApi : Modell-Dropdown
    AssistantSettingsRepository ..> AssistantSettings
    SettingsService ..> AssistantSettings : verwaltet

    note for AssistantSettings "Singleton-Datensatz: es gibt immer genau eine Zeile (id = 1)"
    note for SettingsService "validate: temperature 0.0–2.0, Modell muss in Ollama existieren"
```

*(neu)*: `AssistantSettingsRepository`

---

## 10. Package `status`

Systemzustand für die Status-Seite: Health-Ampeln, Latenzen, CPU/RAM und Log-Puffer.

```mermaid
classDiagram
    direction LR

    class HealthIndicator {
        <<interface>>
        +health() Health
    }

    class OllamaHealthIndicator {
        <<Component>>
        -restClient : RestClient
        -ollamaBaseUrl : String
        +health() Health
        +getLoadedModels() List~String~
    }

    class VoiceHealthIndicator {
        <<Component>>
        -voiceServiceClient : VoiceServiceClient
        +health() Health
    }

    class MetricsService {
        <<Service>>
        -meterRegistry : MeterRegistry
        -systemInfo : SystemInfo
        -sttTimer : Timer
        -llmTimer : Timer
        -ttsTimer : Timer
        -lastLatencies : Map~String, Long~
        +recordStt(ms: long) void
        +recordLlm(ms: long) void
        +recordTts(ms: long) void
        +time(name: String, action: Supplier~T~) T
        +getCpuLoad() double
        +getUsedMemoryMb() long
        +getTotalMemoryMb() long
        +getLastLatencies() Map~String, Long~
        +getSystemStatus() SystemStatus
    }

    class SystemStatus {
        <<record>>
        +cpuLoad : double
        +usedMemoryMb : long
        +totalMemoryMb : long
        +lastSttMs : Long
        +lastLlmMs : Long
        +lastTtsMs : Long
    }

    class AppenderBase~E~ {
        <<abstract>>
        #append(event: E) void*
    }

    class LogBufferAppender {
        -MAX_LINES : int = 200$
        -buffer : Deque~String~$
        -layout : PatternLayout
        #append(event: ILoggingEvent) void
        +getLines() List~String~$
        +getLines(level: Level) List~String~$
        +clear() void$
    }

    %% Klassen aus anderen Packages / Bibliotheken
    class VoiceServiceClient
    class SystemInfo

    HealthIndicator <|.. OllamaHealthIndicator
    HealthIndicator <|.. VoiceHealthIndicator
    AppenderBase <|-- LogBufferAppender : E = ILoggingEvent
    VoiceHealthIndicator --> VoiceServiceClient
    MetricsService --> SystemInfo : OSHI
    MetricsService ..> SystemStatus : liefert

    note for LogBufferAppender "Ringpuffer der letzten 200 Log-Zeilen, eingebunden über logback-spring.xml"
```

*(neu)*: `SystemStatus`
