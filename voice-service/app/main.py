import os
import shutil
from fastapi import FastAPI, UploadFile, File, HTTPException
from faster_whisper import WhisperModel

app = FastAPI(
    title="Jarvis Voice Service",
    description="STT Service mit Faster-Whisper für das Jarvis Projekt",
    version="0.1.0"
)

# Whisper Modell initialisieren (large-v3-turbo auf CPU mit int8 Quantisierung)
MODEL_SIZE = "large-v3-turbo"
DEVICE = "cpu"
COMPUTE_TYPE = "int8"

print(f"Lade Whisper-Modell '{MODEL_SIZE}' auf {DEVICE} ({COMPUTE_TYPE})...")
model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
print("Whisper-Modell erfolgreich geladen!")


@app.get("/health")
def health_check():
    return {
        "status": "UP",
        "stt_model": MODEL_SIZE,
        "device": DEVICE
    }


@app.post("/transcribe")
async def transcribe_audio(file: UploadFile = File(...)):
    """
    Empfängt eine Audiodatei, transkribiert sie mit Faster-Whisper
    und gibt den erkannten Text sowie Spracheigenschaften zurück.
    """
    if not file:
        raise HTTPException(status_code=400, detail="Keine Datei hochgeladen.")

    # Temporäre Datei auf der Festplatte anlegen
    temp_filename = f"temp_{file.filename}"
    try:
        with open(temp_filename, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)

        # Transkription mit Faster-Whisper durchführen
        segments, info = model.transcribe(temp_filename)
        
        # Alle Text-Segmente zusammenfügen
        full_text = " ".join([segment.text for segment in segments]).strip()

        return {
            "text": full_text,
            "language": info.language,
            "probability": round(info.language_probability, 4)
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Fehler bei der Transkription: {str(e)}")

    finally:
        # Temporäre Datei nach der Verarbeitung wieder löschen
        if os.path.exists(temp_filename):
            os.remove(temp_filename)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="127.0.0.1", port=8090, reload=True)