package de.grauk.jarvis.tools.builtin;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class DateTimeTools {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy, HH:mm 'Uhr' (VV)", Locale.GERMAN);

    @Tool(description = "Liefert das aktuelle Datum, den Wochentag und die Uhrzeit des Nutzers. "
            + "Immer verwenden, wenn nach Datum, Wochentag oder Uhrzeit gefragt wird oder du sie für eine "
            + "Berechnung brauchst; niemals raten.")
    public String getCurrentDateTime() {
        return FORMAT.format(ZonedDateTime.now(ZoneId.systemDefault()));
    }
}
