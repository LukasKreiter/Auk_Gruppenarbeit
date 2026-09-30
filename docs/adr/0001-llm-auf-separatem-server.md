# 0001 – LLM und voice-service laufen auf einem separaten Server

- **Status:** angenommen
- **Datum:** 2026-09-30

## Kontext

Ursprünglich sollte alles auf einem Windows-Rechner laufen. Ollama (`qwen3:8b` + `bge-m3`) und
faster-whisper (CUDA) teilen sich dann die GPU; bei 8 GB VRAM war das als größtes Risiko
dokumentiert (Modelle werden ausgelagert → Latenzspitzen).

## Entscheidung

Ollama **und** der `voice-service` laufen auf einem separaten GPU-Server (Aufbau: Kreiter).
`jarvis` (Spring Boot) und der Browser bleiben auf dem Arbeitsplatz-PC.

- `jarvis` bindet weiterhin nur an `127.0.0.1:8080` – das Dashboard ist nicht aus dem LAN erreichbar.
- Ollama und `voice-service` hören auf dem Server im LAN (`0.0.0.0:11434`, `0.0.0.0:8090`).
- Absicherung, da Ollama keine Authentifizierung kennt:
  - Server-Firewall erlaubt 11434 und 8090 **nur** von der IP des Jarvis-PCs.
  - `voice-service` prüft zusätzlich den Header `X-Jarvis-Token` gegen eine Env-Variable.
- Jarvis-Seite konfiguriert nur URLs und Token: `spring.ai.ollama.base-url`, `jarvis.voice.base-url`,
  `jarvis.voice.token`.

## Konsequenzen

- VRAM-Budget des PCs ist kein Thema mehr; auf dem Server gilt es weiterhin (Phase 0 misst dort).
- Netzwerklatenz PC ↔ Server kommt zu STT/LLM/TTS hinzu (im LAN vernachlässigbar, wird aber mitgemessen).
- Server nicht erreichbar → Chat meldet einen verständlichen Fehler (kein Cloud-Fallback, siehe [0005](0005-kein-automatischer-cloud-fallback.md)).
- Tools, die lokal auf dem PC wirken (`openApplication`), bleiben korrekt, weil `jarvis` auf dem PC läuft.
