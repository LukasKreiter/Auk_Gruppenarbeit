import logging
import os
import secrets
import tempfile
from fastapi import Depends, FastAPI, UploadFile, File, Header, HTTPException
from faster_whisper import WhisperModel

app = FastAPI(
    title="Jarvis Voice Service",
    description="STT Service mit Faster-Whisper für das Jarvis Projekt",
    version="0.1.0"
)

log = logging.getLogger("jarvis.voice")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

# Wie spring.servlet.multipart.max-file-size auf der Jarvis-Seite
MAX_UPLOAD_BYTES = 25 * 1024 * 1024

# Whisper Modell initialisieren (large-v3-turbo auf CPU mit int8 Quantisierung)
MODEL_SIZE = "large-v3-turbo"
DEVICE = "cpu"
COMPUTE_TYPE = "int8"

# Gemeinsames Geheimnis mit Jarvis (jarvis.voice.token), siehe ADR 0001
TOKEN = os.environ.get("JARVIS_VOICE_TOKEN", "").strip()
if not TOKEN:
    log.warning("JARVIS_VOICE_TOKEN ist nicht gesetzt – Anfragen werden ohne Token akzeptiert. "
                "Der Server bindet deshalb standardmäßig nur an 127.0.0.1.")

log.info("Lade Whisper-Modell '%s' auf %s (%s)...", MODEL_SIZE, DEVICE, COMPUTE_TYPE)
model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
log.info("Whisper-Modell erfolgreich geladen!")


def require_token(x_jarvis_token: str = Header(default="")):
    if TOKEN and not secrets.compare_digest(x_jarvis_token.encode(), TOKEN.encode()):
        raise HTTPException(status_code=401, detail="Ungültiges oder fehlendes X-Jarvis-Token.")


@app.get("/health", dependencies=[Depends(require_token)])
def health_check():
    return {
        "status": "UP",
        "stt_model": MODEL_SIZE,
        "models": [MODEL_SIZE],
        "device": DEVICE
    }


# Bewusst kein async: FastAPI führt die blockierende Transkription dann im Threadpool aus,
# damit /health währenddessen weiter antwortet.
@app.post("/transcribe", dependencies=[Depends(require_token)])
def transcribe_audio(file: UploadFile = File(...)):
    """
    Empfängt eine Audiodatei, transkribiert sie mit Faster-Whisper
    und gibt den erkannten Text sowie Spracheigenschaften zurück.
    """
    # Temporäre Datei mit zufälligem Namen anlegen (der Client-Dateiname wird nie als Pfad verwendet)
    suffix = os.path.splitext(os.path.basename(file.filename or ""))[1]
    fd, temp_filename = tempfile.mkstemp(prefix="jarvis-stt-", suffix=suffix)
    try:
        with os.fdopen(fd, "wb") as buffer:
            written = 0
            while chunk := file.file.read(1024 * 1024):
                written += len(chunk)
                if written > MAX_UPLOAD_BYTES:
                    raise HTTPException(status_code=413, detail="Audiodatei ist zu groß (maximal 25 MB).")
                buffer.write(chunk)

        # Transkription mit Faster-Whisper durchführen
        segments, info = model.transcribe(temp_filename)

        # Alle Text-Segmente zusammenfügen
        full_text = " ".join([segment.text for segment in segments]).strip()
        if not full_text:
            # Stille/Rauschen: ausdrücklich melden statt leeren Text zu liefern, der erst später im Chat scheitert
            log.info("Transkription ohne erkannten Text (%s)", file.filename)
            raise HTTPException(status_code=422, detail="Keine Sprache erkannt.")

        return {
            "text": full_text,
            "language": info.language,
            "probability": round(info.language_probability, 4)
        }

    except HTTPException:
        raise

    except Exception as e:
        log.exception("Transkription fehlgeschlagen (%s)", file.filename)
        raise HTTPException(status_code=500, detail=f"Fehler bei der Transkription: {str(e)}") from e

    finally:
        # Temporäre Datei nach der Verarbeitung wieder löschen; ein Fehler dabei darf das Ergebnis nicht überdecken
        try:
            os.remove(temp_filename)
        except FileNotFoundError:
            pass
        except OSError:
            log.warning("Temporäre Datei %s konnte nicht gelöscht werden", temp_filename, exc_info=True)


if __name__ == "__main__":
    import uvicorn
    # Im LAN (ADR 0001) mit JARVIS_VOICE_HOST=0.0.0.0 starten; ohne Token nur lokal
    host = os.environ.get("JARVIS_VOICE_HOST", "0.0.0.0" if TOKEN else "127.0.0.1")
    port = int(os.environ.get("JARVIS_VOICE_PORT", "8090"))
    uvicorn.run(app, host=host, port=port)
