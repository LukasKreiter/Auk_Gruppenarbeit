package de.grauk.jarvis.conversation;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class AudioRecorder {

    private TargetDataLine line;
    private final AudioFormat format;
    // volatile: der Aufnahme-Thread muss das Stopp-Signal sehen
    private volatile boolean isRecording = false;
    private ByteArrayOutputStream out;
    private Thread recordingThread;
    private volatile Throwable recordingError;

    public AudioRecorder() {
        // Standard PCM WAV-Format (16kHz, 16 Bit, Mono) - ideal für Faster-Whisper
        this.format = new AudioFormat(16000, 16, 1, true, false);
    }

    public synchronized void startRecording() throws LineUnavailableException {
        if (isRecording) {
            throw new IllegalStateException("Aufnahme läuft bereits.");
        }
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Mikrofon-Format wird nicht unterstützt.");
        }

        line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        out = new ByteArrayOutputStream();
        recordingError = null;
        isRecording = true;

        // Aufnahme in separatem Thread starten
        TargetDataLine source = line;
        ByteArrayOutputStream target = out;
        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try {
                while (isRecording) {
                    int count = source.read(buffer, 0, buffer.length);
                    if (count > 0) {
                        target.write(buffer, 0, count);
                    }
                }
            } catch (RuntimeException e) {
                // wird in stopRecording() gemeldet statt still im Thread zu sterben
                recordingError = e;
            }
        }, "audio-recorder");
        recordingThread.start();
    }

    public synchronized byte[] stopRecording() {
        if (out == null || recordingThread == null) {
            throw new IllegalStateException("Es läuft keine Aufnahme.");
        }
        isRecording = false;
        if (line != null) {
            line.stop();
            line.close();
        }

        // erst nach Ende des Threads lesen, sonst fehlen die letzten Puffer oder es wird parallel geschrieben
        try {
            recordingThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Warten auf das Aufnahme-Ende wurde unterbrochen.", e);
        }
        if (recordingThread.isAlive()) {
            throw new IllegalStateException("Aufnahme-Thread hat sich nicht beendet.");
        }
        recordingThread = null;
        if (recordingError != null) {
            throw new IllegalStateException("Fehler während der Aufnahme.", recordingError);
        }

        byte[] audioData = out.toByteArray();
        out = null;
        return convertToWavByteArray(audioData, format);
    }

    private byte[] convertToWavByteArray(byte[] pcmData, AudioFormat format) {
        try (ByteArrayOutputStream wavOut = new ByteArrayOutputStream();
             AudioInputStream audioStream = new AudioInputStream(
                     new ByteArrayInputStream(pcmData),
                     format,
                     pcmData.length / format.getFrameSize())) {

            AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, wavOut);
            return wavOut.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Fehler beim Erstellen des WAV-Streams", e);
        }
    }
}
