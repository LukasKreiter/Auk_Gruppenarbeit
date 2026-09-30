# 0005 – Kein automatischer Fallback auf das Cloud-Modell

- **Status:** angenommen
- **Datum:** 2026-09-30

## Kontext

Ist der LLM-Server nicht erreichbar, läge es nahe, automatisch auf Claude umzuschalten. Im Claude-Modus
gehen aber Chat-Verlauf, RAG-Chunks aus eigenen Dokumenten und gemerkte Fakten an Anthropic.

## Entscheidung

- Es gibt **keinen** automatischen Wechsel von Ollama zu Claude. Der Provider ändert sich nur durch eine
  bewusste Auswahl in den Einstellungen.
- Ist der gewählte Provider nicht verfügbar, wirft `AssistantService` eine `ProviderUnavailableException`,
  der Chat zeigt einen verständlichen Fehler („LLM-Server nicht erreichbar – in den Einstellungen kann
  Claude gewählt werden“).
- Solange Claude aktiv ist, zeigt der Chat ein sichtbares „☁ Cloud“-Badge.

## Konsequenzen

- Keine privaten Daten verlassen den Rechner, ohne dass jemand es entschieden hat.
- Kein Filtern von Fakten/Dokumenten im Cloud-Modus im MVP; ein „Privat“-Flag für Dokumente/Fakten wäre eine
  spätere Erweiterung.
