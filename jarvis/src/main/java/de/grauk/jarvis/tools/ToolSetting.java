package de.grauk.jarvis.tools;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Einstellung pro Tool (Tabelle {@code tool_setting}); Zeilen werden von {@link ToolRegistry} angelegt. */
@Entity
@Table(name = "tool_setting")
public class ToolSetting {

    @Id
    @Column(name = "tool_name", length = 100)
    private String toolName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "requires_confirmation", nullable = false)
    private boolean requiresConfirmation;

    protected ToolSetting() {
    }

    public ToolSetting(String toolName, boolean enabled, boolean requiresConfirmation) {
        this.toolName = toolName;
        this.enabled = enabled;
        this.requiresConfirmation = requiresConfirmation;
    }

    public String getToolName() {
        return toolName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }
}
