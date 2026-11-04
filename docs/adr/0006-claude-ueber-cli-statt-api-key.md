# 0006 – Claude über die Claude-Code-CLI statt über einen API-Key

- **Status:** angenommen
- **Datum:** 2026-11-04
- **Ersetzt:** den Spring-AI-Anthropic-Starter und den API-Key aus [0002](0002-umschaltbarer-chat-provider.md)

## Kontext

Claude lief bisher über `spring-ai-starter-model-anthropic` und brauchte `ANTHROPIC_API_KEY`, also ein separat
abgerechnetes API-Konto. Auf dem Jarvis-PC ist die Claude-Code-CLI ohnehin mit einem Claude-Abo angemeldet. Der Key
soll wegfallen, ohne dass Tools, Bestätigungen, Verlauf und RAG im Cloud-Modus verloren gehen.

## Entscheidung

- `ClaudeCliChatModel` implementiert `ChatModel` und startet **pro Anfrage einen** versteckten `claude -p`-Prozess.
  Antworttext kommt über `--output-format stream-json`. Kein langlebiger Prozess pro Konversation: Verlauf und RAG
  liegen ohnehin in den Advisors, ein zweiter Zustand in der CLI brächte nur Lebenszyklus-Probleme.
- Der Anthropic-Starter und jede Key-Behandlung sind entfernt. `ANTHROPIC_API_KEY` und `ANTHROPIC_AUTH_TOKEN` werden
  dem Prozess sogar aktiv entzogen, weil die CLI sie sonst der Anmeldung vorziehen würde.
- **Tools laufen über eine lokale MCP-Brücke.** Die CLI kennt keine Spring-AI-`ToolCallback`s; Jarvis bietet die für
  diese Anfrage freigegebenen Tools daher unter `POST /internal/claude-mcp` an. Jeder Lauf bekommt ein eigenes
  Zufalls-Token, das nur solange gilt, wie der Prozess läuft. Die Tool-Schleife läuft in der CLI, die Ausführung bleibt
  in Jarvis: `ListenerToolCallback`, `ConfirmationGate` und Tool-Einstellungen gelten unverändert.
- Der MCP-Endpunkt ist bewusst von Hand gebaut (vier JSON-RPC-Methoden) statt über den Spring-AI-MCP-Starter. Der
  Starter veröffentlicht Tools pro Anwendung; hier hängen Tool-Liste und Listener an der einzelnen Anfrage.
- Alles Eigene der CLI ist abgeschaltet (`--tools=`, `--setting-sources=`, `--strict-mcp-config`,
  `--disable-slash-commands`, `--no-session-persistence`, leeres Arbeitsverzeichnis). Eine Prompt-Injection über
  RAG-Dokumente erreicht damit keine Datei- oder Shell-Tools der CLI, sondern nur die Tools, die Jarvis freigibt.
- „Verfügbar“ heißt jetzt: `claude auth status` endet mit Exit 0 (30 s gecacht).
- Der Provider-Wert bleibt `ANTHROPIC`: Er benennt den Anbieter, steht in `assistant_settings` und `message`, und
  ein neuer Name brächte nur eine Migration.

## Konsequenzen

- Voraussetzung auf dem Jarvis-PC: Claude Code installiert und `claude auth login` unter **demselben Windows-Konto**,
  unter dem Jarvis läuft. Das Programm ist über `JARVIS_CLAUDE_CLI` einstellbar (Standard `claude` vom PATH).
- Die Nutzung zählt gegen die Limits des Abos statt pro Token abgerechnet zu werden; ist das Limit erreicht, meldet
  der Chat das als Fehler. Kein automatischer Wechsel auf Ollama ([0005](0005-kein-automatischer-cloud-fallback.md)).
- Jede Anfrage kostet einige Sekunden Prozessstart zusätzlich.
- Temperatur wirkt bei Claude nicht mehr, die CLI kennt sie nicht. Die Grenze 0–1 entfällt daher; der Wert bleibt für
  Ollama gespeichert. Der Thinking-Modus wird auf `--effort high` bzw. `low` abgebildet.
- Datenschutz wie zuvor: Im Cloud-Modus gehen Verlauf, RAG-Chunks und Fakten an Anthropic.
- Jarvis hängt am Ausgabeformat der CLI. Ändert es sich, fällt das in `ClaudeStreamParser` auf.
- `LocalOnlyFilter` lässt für genau diesen Pfad Anfragen ohne `Origin` durch, weil die CLI kein Browser ist. Das
  Bearer-Token ersetzt dort den CSRF-Schutz: Kein Browser schickt es automatisch mit, und eine fremde `Origin` wird
  weiterhin abgelehnt.
