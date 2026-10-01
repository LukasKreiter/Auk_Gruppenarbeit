package de.grauk.jarvis.conversation;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class AudioRecorder {

    private TargetDataLine line;
    private AudioFormat format;
    private boolean isRecording = false;
    private ByteArrayOutputStream out;

    public AudioRecorder() {
        // Standard PCM WAV-Format (16kHz, 16 Bit, Mono) - ideal für Faster-Whisper
        this.format = new AudioFormat(16000, 16, 1, true, false);
    }

    public void startRecording() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Mikrofon-Format wird nicht unterstützt.");
        }

        line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        out = new ByteArrayOutputStream();
        isRecording = true;

        // Aufnahme in separatem Thread starten
        Thread recordingThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            while (isRecording) {
                int count = line.read(buffer, 0, buffer.length);
                if (count > 0) {
                    out.write(buffer, 0, count);
                }
            }
        });
        recordingThread.start();
    }

    public byte[] stopRecording() {
        isRecording = false;
        if (line != null) {
            line.stop();
            line.close();
        }

        byte[] audioData = out.toByteArray();
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