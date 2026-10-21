package de.grauk.jarvis.voice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Service
public class VoiceServiceClient {

    private final String piperPath = "C:\\piper\\piper.exe";

    private final VoiceSelector voiceSelector;

    public VoiceServiceClient(
            VoiceSelector voiceSelector) {

        this.voiceSelector = voiceSelector;
    }

    public byte[] synthesize(String text) {

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Text darf nicht leer sein."
            );
        }

        VoiceSelector.Voice voice = voiceSelector.select();

        Path outputFile = Paths.get(
                "C:\\piper\\jarvis-output.wav"
        );

        try {
            // Alte Datei löschen
            Files.deleteIfExists(outputFile);

            ProcessBuilder processBuilder = new ProcessBuilder(
                    piperPath,
                    "--model",
                    voice.model(),
                    "--config",
                    voice.config(),
                    "--output_file",
                    outputFile.toString()
            );

            processBuilder.redirectErrorStream(false);

            Process process = processBuilder.start();

            // Text an Piper schicken
            process.getOutputStream().write(
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            process.getOutputStream().write('\n');
            process.getOutputStream().close();

            // Fehlerausgabe lesen
            String errorOutput;

            try (InputStream stderr = process.getErrorStream()) {
                errorOutput = new String(
                        readAllBytes(stderr),
                        java.nio.charset.StandardCharsets.UTF_8
                );
            }

            boolean finished = process.waitFor(
                    60,
                    TimeUnit.SECONDS
            );

            if (!finished) {
                process.destroyForcibly();

                throw new IllegalStateException(
                        "Piper hat das Zeitlimit überschritten."
                );
            }

            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "Piper konnte die Sprache nicht erzeugen: "
                                + errorOutput
                );
            }

            // Prüfen, ob WAV-Datei erzeugt wurde
            if (!Files.exists(outputFile)) {
                throw new IllegalStateException(
                        "Piper hat keine WAV-Datei erzeugt."
                );
            }

            // WAV-Datei einlesen
            byte[] audio = Files.readAllBytes(outputFile);

            if (audio.length == 0) {
                throw new IllegalStateException(
                        "Die erzeugte WAV-Datei ist leer."
                );
            }

            return audio;

        } catch (IOException e) {

            throw new IllegalStateException(
                    "Fehler beim Starten oder Ausführen von Piper.",
                    e
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Piper wurde unterbrochen.",
                    e
            );
        }
    }

    private byte[] readAllBytes(InputStream inputStream)
            throws IOException {

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        byte[] buffer = new byte[8192];

        int bytesRead;

        while ((bytesRead = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }

        return output.toByteArray();
    }
}

