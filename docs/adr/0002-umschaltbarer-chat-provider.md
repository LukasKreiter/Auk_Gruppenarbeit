# 0002 – Chat-Provider zur Laufzeit umschaltbar (Ollama ↔ Claude)

- **Status:** angenommen
- **Datum:** 2026-09-30

## Kontext

Neben dem lokalen Modell (Ollama auf dem LLM-Server) soll ein Cloud-Modell (Anthropic Claude)
nutzbar sein – z. B. für schwierigere Fragen oder wenn bessere Tool-Calling-Qualität gebraucht wird.
Die README verspricht bereits: „Modellwechsel wirkt per Request, kein Neustart“.

## Entscheidung

- Beide Spring-AI-Starter sind im Build: `spring-ai-starter-model-ollama` und
  `spring-ai-starter-model-anthropic`. Pro Provider existiert ein eigener `ChatClient`.
- `assistant_settings` bekommt die Spalte `provider` (`OLLAMA` | `ANTHROPIC`); `model` bleibt und
  bezieht sich auf den gewählten Provider.
- `ChatProviderRegistry` (Paket `assistant/`) entscheidet **pro Request** anhand der Settings,
  welcher `ChatClient` benutzt wird, und liefert Verfügbarkeit + Modelllisten.
- Modellliste: Ollama live aus `/api/tags`, Claude als feste Liste aus `application.yml`
  (`jarvis.assistant.anthropic-models`, Default `claude-sonnet-5-5`, günstig: `claude-haiku-4-5-20251001`).
- API-Key ausschließlich über die Umgebungsvariable `ANTHROPIC_API_KEY` – nie in DB, `application.yml`
  oder Git. Fehlt er, ist Claude im Dropdown ausgegraut.
- Im Chat ist sichtbar, welcher Provider antwortet (Badge „☁ Cloud“); `message` speichert `provider` + `model`.

## Konsequenzen

- Provider-spezifische Optionen (Temperatur, Thinking-Modus) werden in `AssistantService` je Provider
  gemappt (`OllamaChatOptions` bzw. `AnthropicChatOptions`).
- Tools, Chat-Memory und RAG laufen provider-unabhängig über dieselben Advisors / `ToolCallback`s.
- Embeddings wechseln **nicht** mit, siehe [0003](0003-embeddings-immer-bge-m3.md).
- Wenn beide Starter auf dem Classpath sind, darf kein automatisch konfigurierter
  `ChatClient.Builder` mehrdeutig werden → `ChatClient`s werden in `ChatClientConfig` explizit gebaut.
  Exakte Property-/Artefaktnamen beim Umsetzen gegen Spring AI 2.0.1 prüfen.
