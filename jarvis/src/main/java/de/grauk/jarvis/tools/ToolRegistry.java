package de.grauk.jarvis.tools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Sammelt alle Beans mit {@code @Tool}-Methoden, gleicht sie mit {@code tool_setting} ab und liefert die
 * aktiven Tools. Die Einstellungen werden bei jedem Aufruf frisch gelesen.
 */
@Service
public class ToolRegistry {

    public record ToolInfo(String name, String description, boolean enabled, boolean requiresConfirmation) {}

    private final Supplier<List<Object>> toolObjects;
    private final ToolSettingRepository repository;
    private final ConfirmationGate gate;

    private List<ToolCallback> callbacks;
    private Map<String, Boolean> confirmationDefaults;

    @Autowired
    public ToolRegistry(ListableBeanFactory beanFactory, ToolSettingRepository repository, ConfirmationGate gate) {
        this(() -> discover(beanFactory), repository, gate);
    }

    ToolRegistry(Supplier<List<Object>> toolObjects, ToolSettingRepository repository, ConfirmationGate gate) {
        this.toolObjects = toolObjects;
        this.repository = repository;
        this.gate = gate;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        syncSettings();
    }

    /** Aktive Tools, jeweils mit Listener-Wrapper (und ggf. Bestätigung). */
    public List<ToolCallback> enabledCallbacks() {
        Map<String, ToolSetting> settings = syncSettings();
        List<ToolCallback> result = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            ToolSetting setting = settings.get(callback.getToolDefinition().name());
            if (setting.isEnabled()) {
                result.add(new ListenerToolCallback(callback, effectiveConfirmation(setting), gate));
            }
        }
        return result;
    }

    public List<ToolInfo> all() {
        Map<String, ToolSetting> settings = syncSettings();
        List<ToolInfo> result = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            var definition = callback.getToolDefinition();
            ToolSetting setting = settings.get(definition.name());
            result.add(new ToolInfo(definition.name(), definition.description(),
                    setting.isEnabled(), effectiveConfirmation(setting)));
        }
        return result;
    }

    public void update(String toolName, boolean enabled, boolean requiresConfirmation) {
        ToolSetting setting = syncSettings().get(toolName);
        if (setting == null || callbacks.stream().noneMatch(c -> c.getToolDefinition().name().equals(toolName))) {
            throw new IllegalArgumentException("Unbekanntes Tool: " + toolName);
        }
        if (!requiresConfirmation && confirmationDefaults.getOrDefault(toolName, false)) {
            throw new IllegalArgumentException("Das Tool '" + toolName + "' erfordert immer eine Bestätigung.");
        }
        setting.setEnabled(enabled);
        setting.setRequiresConfirmation(requiresConfirmation);
        repository.save(setting);
    }

    /** {@link RequiresConfirmation} ist eine Untergrenze: die Einstellung kann sie nicht abschalten. */
    private boolean effectiveConfirmation(ToolSetting setting) {
        return setting.isRequiresConfirmation() || confirmationDefaults.getOrDefault(setting.getToolName(), false);
    }

    /** Legt fehlende Zeilen an (bestehende bleiben unangetastet) und liefert die aktuellen Einstellungen. */
    private synchronized Map<String, ToolSetting> syncSettings() {
        if (callbacks == null) {
            List<ToolCallback> found = new ArrayList<>();
            Map<String, Boolean> defaults = new HashMap<>();
            for (Object object : toolObjects.get()) {
                Map<String, Boolean> objectDefaults = confirmationDefaults(object.getClass());
                for (String name : objectDefaults.keySet()) {
                    if (defaults.containsKey(name)) {
                        throw new IllegalStateException("Doppelter Tool-Name '" + name + "' (u. a. in "
                                + ClassUtils.getUserClass(object).getName() + ")");
                    }
                }
                found.addAll(List.of(MethodToolCallbackProvider.builder().toolObjects(object).build()
                        .getToolCallbacks()));
                defaults.putAll(objectDefaults);
            }
            callbacks = found;
            confirmationDefaults = defaults;
        }
        Map<String, ToolSetting> settings = new LinkedHashMap<>();
        repository.findAll().forEach(s -> settings.put(s.getToolName(), s));
        for (ToolCallback callback : callbacks) {
            String name = callback.getToolDefinition().name();
            if (!settings.containsKey(name)) {
                ToolSetting created = new ToolSetting(name, true, confirmationDefaults.getOrDefault(name, false));
                settings.put(name, repository.save(created));
            }
        }
        return settings;
    }

    /** Toolname → true, wenn die Methode mit {@link RequiresConfirmation} markiert ist. Leer = kein Tool-Bean. */
    private static Map<String, Boolean> confirmationDefaults(Class<?> type) {
        Map<String, Boolean> result = new HashMap<>();
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(ClassUtils.getUserClass(type))) {
            Tool tool = AnnotatedElementUtils.findMergedAnnotation(method, Tool.class);
            if (tool != null) {
                String name = tool.name().isBlank() ? method.getName() : tool.name();
                result.put(name, AnnotatedElementUtils.hasAnnotation(method, RequiresConfirmation.class));
            }
        }
        return result;
    }

    private static List<Object> discover(ListableBeanFactory beanFactory) {
        List<Object> result = new ArrayList<>();
        for (String beanName : beanFactory.getBeanNamesForType(Object.class, false, false)) {
            Class<?> type = beanFactory.getType(beanName, false);
            if (type != null && ClassUtils.getUserClass(type).getPackageName().startsWith("de.grauk.jarvis")
                    && !confirmationDefaults(type).isEmpty()) {
                result.add(beanFactory.getBean(beanName));
            }
        }
        return result;
    }
}
