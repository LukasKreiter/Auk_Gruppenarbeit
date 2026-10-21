package de.grauk.jarvis.voice;

import de.grauk.jarvis.settings.SettingsService;
import de.grauk.jarvis.status.MetricsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class VoiceServiceClient {

    private static final Logger log = LoggerFactory.getLogger(VoiceServiceClient.class);

    public static final int MAX_TEXT_LENGTH = 2000;

    /** Piper ist CPU-lastig; mehr gleichzeitige Prozesse machen nur alle langsamer. */
    static final int MAX_CONCURRENT = 2;

    static final long TIMEOUT_SECONDS = 60;

    private final String piperPath;

    private final VoiceSelector voiceSelector;

    private final SettingsService settings;

    private final MetricsService metrics;

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT);

    public VoiceServiceClient(
            VoiceSelector voiceSelector,
            SettingsService settings,
            MetricsService metrics,
            @Value("${jarvis.voice.piper.executable}") String piperPath) {

        this.voiceSelector = voiceSelector;
        this.settings = settings;
        this.metrics = metrics;
        this.piperPath = piperPath;
    }

    /** @param language Sprache des Textes für die Stimmenwahl; {@code null} = Deutsch */
    public byte[] synthesize(String text, String language) {

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Text darf nicht leer sein."
            );
        }

        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "Text ist zu lang (maximal " + MAX_TEXT_LENGTH + " Zeichen)."
            );
        }

        if (!settings.isTtsEnabled()) {
            throw new VoiceException(
                    "Die Sprachausgabe ist in den Einstellungen deaktiviert."
            );
        }

        VoiceSelector.Voice voice = voiceSelector.select(language);

        if (!permits.tryAcquire()) {
            throw new VoiceException(
                    "Die Sprachausgabe ist gerade ausgelastet. Bitte gleich nochmal versuchen."
            );
        }

        try {
            long start = System.nanoTime();
            byte[] audio = runPiper(text, voice);
            metrics.recordTts(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            return audio;
        } finally {
            permits.release();
        }
    }

    private byte[] runPiper(String text, VoiceSelector.Voice voice) {

        Path outputFile = null;
        Path errorFile = null;
        Process process = null;

        try {
            // Eigene Dateien pro Anfrage, damit parallele Anfragen sich nicht überschreiben
            outputFile = Files.createTempFile("jarvis-tts-", ".wav");
            errorFile = Files.createTempFile("jarvis-tts-", ".log");
            Files.delete(outputFile);

            List<String> command = new ArrayList<>(List.of(
                    piperPath,
                    "--model",
                    voice.model(),
                    "--config",
                    voice.config(),
                    "--output_file",
                    outputFile.toString()
            ));

            if (voice.speaker() != 0) {
                command.add("--speaker");
                command.add(String.valueOf(voice.speaker()));
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command);

            // Fehlerausgabe in eine Datei, damit das Lesen nicht blockiert und das Zeitlimit greift
            processBuilder.redirectError(errorFile.toFile());
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            process = processBuilder.start();

            // Text in eigenem Thread an Piper schicken: liest Piper nicht, blockiert sonst das Schreiben
            // und das Zeitlimit unten greift nie
            Process running = process;
            Thread.ofVirtual().name("piper-stdin").start(() -> {
                try (OutputStream stdin = running.getOutputStream()) {
                    stdin.write(text.getBytes(StandardCharsets.UTF_8));
                    stdin.write('\n');
                } catch (IOException e) {
                    // Piper ist schon beendet; Exit-Code und Fehlerausgabe unten erklären den Grund
                    log.debug("Text konnte nicht an Piper übergeben werden: {}", e.toString());
                }
            });

            boolean finished = process.waitFor(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!finished) {
                throw new VoiceException(
                        "Die Sprachausgabe hat das Zeitlimit überschritten."
                );
            }

            if (process.exitValue() != 0) {
                throw new VoiceException(
                        "Die Sprachausgabe ist fehlgeschlagen.",
                        piperError("Piper beendet mit Exit-Code " + process.exitValue(), errorFile)
                );
            }

            // Prüfen, ob WAV-Datei erzeugt wurde
            if (!Files.exists(outputFile)) {
                throw new VoiceException(
                        "Die Sprachausgabe hat keine Audiodatei erzeugt.",
                        piperError("Keine WAV-Datei", errorFile)
                );
            }

            // WAV-Datei einlesen
            byte[] audio = Files.readAllBytes(outputFile);

            if (audio.length == 0) {
                throw new VoiceException(
                        "Die Sprachausgabe hat eine leere Audiodatei erzeugt.",
                        piperError("Leere WAV-Datei", errorFile)
                );
            }

            return audio;

        } catch (IOException e) {

            throw new VoiceException(
                    "Die Sprachausgabe konnte nicht gestartet werden.",
                    e
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new VoiceException(
                    "Die Sprachausgabe wurde unterbrochen.",
                    e
            );

        } finally {
            stop(process);
            deleteQuietly(outputFile);
            deleteQuietly(errorFile);
        }
    }

    /** Ursache mit Piper-Fehlerausgabe; tolerant dekodiert, weil Windows-Konsolen nicht immer UTF-8 schreiben. */
    private static IllegalStateException piperError(String what, Path errorFile) {
        String stderr;
        try {
            stderr = new String(Files.readAllBytes(errorFile), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            stderr = "(Fehlerausgabe nicht lesbar: " + e + ")";
        }
        return new IllegalStateException(what + (stderr.isEmpty() ? "" : ": " + stderr));
    }

    /** Beendet einen noch laufenden Prozess und wartet kurz, damit er die Temp-Dateien freigibt (Windows). */
    private static void stop(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroyForcibly();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("Piper-Prozess {} ließ sich nicht beenden", process.pid());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Temp-Datei bleibt liegen; kein Grund, die Antwort scheitern zu lassen
            log.warn("Temp-Datei {} konnte nicht gelöscht werden: {}", file, e.toString());
        }
    }
}
