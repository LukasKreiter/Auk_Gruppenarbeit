# Risikoregister – Jarvis

Zuletzt aktualisiert: 2026-10-01 · Review: am Ende jeder Phase (0–7)

## Bewertung

Score = Wahrscheinlichkeit (W) × Auswirkung (A), je Skala 1–5.

| Score | Stufe | Handlung |
|-------|-------|----------|
| 1–5   | Niedrig | beobachten |
| 6–11  | Mittel | Maßnahme einplanen |
| 12–25 | Hoch | sofort angehen, vor nächster Phase klären |

**Status:** Offen · In Arbeit · Gemindert · Akzeptiert · Geschlossen

**Rollen:** Kreiter (Server), Brenner/Hiebler (Backend), Zheng/Zugaj (Frontend), Steinwidder (TTS), Radaelli (STT)

## Übersicht

| ID | Risiko | Kategorie | Phase | W | A | Score | Verantwortlich | Status |
|----|--------|-----------|-------|---|---|-------|----------------|--------|
| R01 | VRAM-Budget (16 GB) → Modell-Auslagerung, Latenzspitzen | Technik | 0 | 3 | 4 | **12** | Kreiter | Offen |
| R02 | Kaltstart nach Leerlauf (Ollama entlädt nach 5 min) | Performance | 1 | 4 | 2 | 8 | Brenner | Gemindert |
| R03 | Thinking-Modus von qwen3 erhöht Latenz stark | Performance | 4 | 3 | 3 | 9 | Hiebler | Gemindert |
| R04 | Tool-Calling-Qualität modellabhängig | Technik | 5 | 3 | 4 | **12** | Brenner | Gemindert |
| R05 | Piper-Projektstatus / Stimmen-Downloads | Abhängigkeit | 0, 3 | 2 | 3 | 6 | Steinwidder | Offen |
| R06 | Browser-Audioformat (webm/opus vs. ogg/opus) | Technik | 2 | 2 | 3 | 6 | Radaelli | Gemindert |
| R07 | Versions-/Koordinatenänderungen Spring Boot / Spring AI | Abhängigkeit | 0 | 3 | 3 | 9 | Brenner | Offen |
| R08 | CUDA-/cuDNN-/CTranslate2-Versionskonflikt im voice-service | Technik | 0, 2 | 4 | 4 | **16** | Radaelli | Offen |
| R09 | Prompt-Injection über Dokumente (RAG) oder Tool-Ergebnisse | Sicherheit | 5, 6 | 3 | 4 | **12** | Hiebler | Offen |
| R10 | `openApplication` führt unerwünschte Programme aus | Sicherheit | 5 | 2 | 5 | **10** | Brenner | Gemindert |
| R11 | Datenschutz: Anthropic-Provider sendet Inhalte in die Cloud | Datenschutz | 4 | 3 | 4 | **12** | Kreiter | Offen |
| R12 | API-Key (`ANTHROPIC_API_KEY`) versehentlich committed | Sicherheit | alle | 2 | 5 | **10** | Kreiter | Gemindert |
| R13 | Halluzinationen / falsche Antworten, auch bei RAG-Kontext | Qualität | 6 | 4 | 3 | **12** | Hiebler | Offen |
| R14 | Vector-Store (JSON) und DB laufen auseinander | Daten | 6 | 3 | 3 | 9 | Brenner | Offen |
| R15 | Embedding-Modellwechsel macht Index unbrauchbar | Daten | 6 | 2 | 3 | 6 | Brenner | Offen |
| R16 | Datenverlust / Korruption der H2-Datei, kein Backup | Daten | 1 | 2 | 4 | 8 | Kreiter | Offen |
| R17 | Flyway-Migrationskonflikte im Team (`ddl-auto: validate`) | Team | 1+ | 4 | 3 | **12** | Hiebler | Offen |
| R18 | Gesamtlatenz Sprache (STT + LLM + TTS) zu hoch | Performance | 3 | 3 | 4 | **12** | Radaelli | Offen |
| R19 | Falsche Spracherkennung (lingua) bei kurzen Antworten → falsche Stimme | Qualität | 3 | 3 | 2 | 6 | Steinwidder | Offen |
| R20 | Schnittstelle Backend ↔ voice-service ungeklärt oder geändert | Team | 2, 3 | 3 | 3 | 9 | Brenner | Offen |
| R21 | Unterschiedliche Hardware der Teammitglieder | Team | 0 | 4 | 3 | **12** | Kreiter | Offen |
| R22 | Timer gehen bei Neustart verloren (`setTimer` nur im Speicher) | Technik | 5 | 4 | 2 | 8 | Brenner | Offen |
| R23 | Wetter-API (Open-Meteo) nicht erreichbar | Abhängigkeit | 5 | 2 | 1 | 2 | Brenner | Akzeptiert |
| R24 | Modell wird in Ollama-Library geändert oder entfernt | Abhängigkeit | alle | 2 | 3 | 6 | Kreiter | Offen |
| R25 | Mikrofonzugriff blockiert (Browser-Berechtigung, nicht-localhost) | Technik | 2 | 3 | 3 | 9 | Zheng | Offen |
| R26 | Zeitplan / Scope Creep (Phase 7, Always-on-Listener) | Projekt | alle | 4 | 3 | **12** | Kreiter | Offen |
| R27 | Ungeschützte Dashboard-Endpunkte (keine Auth im MVP) | Sicherheit | 4 | 2 | 3 | 6 | Kreiter | Akzeptiert |

## Details und Maßnahmen

### Aus der Planung übernommen

**R01 – VRAM-Budget (16 GB).** qwen3:8b, Whisper und bge-m3 teilen sich die GPU. Bei Bedarf lagert Ollama Modelle aus, das erzeugt Latenzspitzen.
- Maßnahme: in Phase 0 mit `nvidia-smi` messen; zwei Konfigurationsprofile (voll auf GPU / reduziert) dokumentieren.
- Auslöser für Plan B: dauerhaftes Auslagern im Betrieb → kleineres Modell (qwen3:4b) oder Whisper int8.

**R02 – Kaltstart nach Leerlauf.** Ollama entlädt Modelle standardmäßig nach 5 min.
- Maßnahme: `keep-alive: 30m` (bereits in `application.yml` gesetzt); alternativ `-1`.

**R03 – Thinking-Modus.** Erhöht die Antwortzeit deutlich.
- Maßnahme: standardmäßig aus, per Setting (`think_mode`) einschaltbar.

**R04 – Tool-Calling-Qualität.** Hängt vom Modell ab.
- Maßnahme: Fallback `llama3.1:8b` dokumentiert; in Phase 5 pro Tool einen Testfall festhalten.

**R05 – Piper-Projektstatus.** Das Original-Repo ist archiviert; Nachfolger ist `piper1-gpl` (PyPI `piper-tts`).
- Maßnahme: in Phase 0 prüfen, ob Stimmen-Downloads (de_DE-thorsten, en_US-lessac) funktionieren; Stimmen-Dateien lokal im Repo-Umfeld (`models/`) ablegen bzw. Download-Skript dokumentieren.

**R06 – Browser-Audioformat.** Edge/Chrome liefern webm/opus, Firefox ogg/opus; faster-whisper dekodiert beides über PyAV.
- Maßnahme: pytest mit Beispieldateien in beiden Formaten.

**R07 – Versionsänderungen.** Artefaktnamen von Spring Boot / Spring AI haben sich zwischen Milestones geändert.
- Maßnahme: aktuelle Koordinaten von start.spring.io übernehmen, Versionen im `pom.xml` fixieren, Dependabot aktivieren.

### Neu ergänzt

**R08 – CUDA-/cuDNN-Konflikt.** faster-whisper nutzt CTranslate2; die Version muss zu CUDA und cuDNN passen, sonst startet die GPU-Erkennung nicht oder fällt still auf CPU zurück.
- Maßnahme: benötigte Versionen im README festhalten, `/health` meldet das genutzte Device; Fallback `device=cpu` mit kleinerem Modell.

**R09 – Prompt-Injection.** Hochgeladene Dokumente oder Tool-Ergebnisse können Anweisungen enthalten, die das Modell befolgt.
- Maßnahme: Kontext im Prompt klar als Daten kennzeichnen; riskante Tools nur mit Bestätigung; keine Tools mit Schreibzugriff ohne Freigabe.

**R10 – `openApplication`.** Das Starten von Programmen ist ein Missbrauchsrisiko.
- Maßnahme: Allowlist in `application.yml`, standardmäßig „Bestätigung nötig“, keine frei wählbaren Pfade oder Argumente.

**R11 – Datenschutz bei Cloud-Provider.** Die Architektur ist „komplett lokal“, der Anthropic-Provider sendet Eingaben jedoch an einen externen Dienst.
- Maßnahme: Standardprovider `OLLAMA` beibehalten; im Dashboard sichtbar kennzeichnen, wenn ein Cloud-Modell aktiv ist; keine Dokument-/Faktendaten ungefragt an die Cloud geben.

**R12 – Secret-Leak.** Der Key wird nur aus der Umgebungsvariable gelesen (ADR 0002).
- Maßnahme: GitHub Secret Scanning und Push Protection aktivieren; `.env` in `.gitignore`.

**R13 – Halluzinationen.** Das Modell kann falsche Fakten liefern oder RAG-Kontext falsch wiedergeben.
- Maßnahme: `similarity-threshold` kalibrieren, Quellenangabe in der Antwort, Testfragen zu bekannten Dokumenten in Phase 6.

**R14 – Vector-Store und DB inkonsistent.** Fakten liegen in `memory_fact` und im JSON-Vector-Store; beim Löschen oder Absturz können beide abweichen.
- Maßnahme: Löschen immer in beiden Speichern in einer Service-Methode; Re-Index-Funktion bereitstellen.

**R15 – Embedding-Modellwechsel.** Vektoren verschiedener Modelle sind nicht vergleichbar.
- Maßnahme: Modellname mit dem Index speichern; bei Abweichung Neuindexierung erzwingen (bge-m3 ist per ADR 0003 fix).

**R16 – H2-Datenverlust.** Die DB ist eine einzelne Datei (`./data/jarvis`).
- Maßnahme: `data/` in `.gitignore`, regelmäßige Kopie der Datei (z. B. im `start-all.ps1`), Export von Konversationen in Phase 7.

**R17 – Migrationskonflikte.** Mehrere Personen legen parallel `V<n>__*.sql` an; mit `ddl-auto: validate` startet die App dann nicht.
- Maßnahme: Versionsnummern vorab im Team vergeben, Migrationen nur per Pull Request, CI führt `mvn verify` mit frischer DB aus.

**R18 – Gesamtlatenz Sprache.** STT + LLM + TTS addieren sich; zu lange Wartezeit macht den Assistenten unbrauchbar.
- Maßnahme: Latenzen pro Stufe messen (Message-Datensatz, Status-Seite), Zielwert festlegen; später satzweises TTS beim Streaming (Phase 7).

**R19 – Falsche Stimme.** `lingua` ist bei sehr kurzen Texten unsicher.
- Maßnahme: erkannte STT-Sprache als Fallback nutzen; Mindestlänge/Konfidenz prüfen.

**R20 – Schnittstelle voice-service.** STT, TTS und Backend werden von verschiedenen Personen gebaut.
- Maßnahme: API-Vertrag (`/transcribe`, `/synthesize`, `/health`) früh als OpenAPI/Markdown festhalten; FastAPI erzeugt das Schema automatisch.

**R21 – Unterschiedliche Hardware.** Die Planung geht von 16 GB VRAM aus; andere Rechner im Team haben ggf. weniger.
- Maßnahme: Konfigurationsprofile für 8 GB / 16 GB; Mindestanforderungen im README.

**R22 – Verlorene Timer.** Timer im `TaskScheduler` überleben keinen Neustart.
- Maßnahme: Timer in der DB persistieren und beim Start neu planen, oder als bekannte Einschränkung dokumentieren.

**R23 – Open-Meteo.** Einziges Tool mit Internetzugriff.
- Maßnahme: Timeout (5 s) ist gesetzt; verständliche Fehlermeldung statt Absturz.

**R24 – Modelländerungen.** Modelle in der Ollama-Library können aktualisiert oder entfernt werden.
- Maßnahme: Modell-Tags exakt festhalten; Modellliste im README mit Stand-Datum.

**R25 – Mikrofonzugriff.** Browser erlauben `getUserMedia` nur auf `localhost` oder HTTPS.
- Maßnahme: Dashboard nur über `http://localhost:8080` nutzen (nicht per LAN-IP); verständliche Meldung bei verweigerter Berechtigung.

**R26 – Scope Creep.** Phase 7 und Erweiterungen (Always-on, Smart-Home) können die Kernfunktionen verdrängen.
- Maßnahme: Phasen 0–6 als verbindlichen Umfang festlegen; Phase 7 nur bei verbleibender Zeit.

**R27 – Keine Authentifizierung.** Der Server bindet nur an `127.0.0.1`; die H2-Konsole ist deaktiviert.
- Maßnahme: Risiko akzeptiert für den MVP; vor jeder Freigabe im LAN Basic-Auth einführen.

## Änderungsverlauf

| Datum | Änderung | Von |
|-------|----------|-----|
| 2026-10-01 | Erstfassung: R01–R07 aus der Planung, R08–R27 ergänzt | – |
