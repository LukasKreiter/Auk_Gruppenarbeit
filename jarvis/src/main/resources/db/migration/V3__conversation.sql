-- Konversationen, Nachrichten und Tool-Aufrufe (Hiebler, Paket conversation/).
-- Löschen einer Konversation entfernt per ON DELETE CASCADE auch Nachrichten und Tool-Aufrufe.
-- SPRING_AI_CHAT_MEMORY (V1) hat bewusst keinen FK (conversation_id ist dort ein String);
-- ConversationService.delete() räumt sie über ChatMemory.clear() auf.

CREATE TABLE conversation (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    title      VARCHAR(200) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_conversation_updated_at ON conversation (updated_at DESC);

CREATE TABLE message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT      NOT NULL,
    role            VARCHAR(20) NOT NULL,
    content         CLOB        NOT NULL,
    language        VARCHAR(10),
    provider        VARCHAR(20),
    model           VARCHAR(100),
    stt_ms          BIGINT,
    llm_ms          BIGINT,
    tts_ms          BIGINT,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE CASCADE
);
CREATE INDEX idx_message_conversation ON message (conversation_id, created_at, id);

CREATE TABLE tool_call (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT       NOT NULL,
    tool_name  VARCHAR(100) NOT NULL,
    arguments  CLOB,
    result     CLOB,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tool_call_message FOREIGN KEY (message_id)
        REFERENCES message (id) ON DELETE CASCADE
);
CREATE INDEX idx_tool_call_message ON tool_call (message_id);
