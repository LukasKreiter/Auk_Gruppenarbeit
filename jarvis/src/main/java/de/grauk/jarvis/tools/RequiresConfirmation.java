package de.grauk.jarvis.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markiert eine {@code @Tool}-Methode, die etwas auf dem PC ausführt. Beim Anlegen der
 * {@code tool_setting}-Zeile ist {@code requires_confirmation} dann standardmäßig true.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresConfirmation {
}
