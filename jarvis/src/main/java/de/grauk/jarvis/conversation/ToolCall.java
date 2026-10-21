package de.grauk.jarvis.conversation;

import java.time.LocalDateTime;

import de.grauk.jarvis.tools.ToolCallStatus;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Ein Tool-Aufruf zu einer Assistenten-Nachricht (Tabelle {@code tool_call}). */
@Entity
@Table(name = "tool_call")
public class ToolCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    @Lob
    private String arguments;

    @Lob
    private String result;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ToolCallStatus status = ToolCallStatus.OK;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ToolCall() {
    }

    public ToolCall(long messageId, String toolName, String arguments, String result) {
        this(messageId, toolName, arguments, result, ToolCallStatus.OK);
    }

    public ToolCall(long messageId, String toolName, String arguments, String result, ToolCallStatus status) {
        this.messageId = messageId;
        this.toolName = toolName;
        this.arguments = arguments;
        this.result = result;
        this.status = status == null ? ToolCallStatus.OK : status;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public Long getMessageId() { return messageId; }
    public String getToolName() { return toolName; }
    public String getArguments() { return arguments; }
    public String getResult() { return result; }
    public ToolCallStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
