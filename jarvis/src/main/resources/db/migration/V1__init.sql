-- KI-Kern (Brenner): Tool-Einstellungen, Wissen, Gedächtnis, Spring-AI-Chat-Memory.
-- conversation, message, tool_call und assistant_settings folgen in eigenen Migrationen (Hiebler).

CREATE TABLE tool_setting (
    tool_name             VARCHAR(100) PRIMARY KEY,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    requires_confirmation BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE knowledge_document (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    filename       VARCHAR(255)  NOT NULL,
    chunks         INT           NOT NULL DEFAULT 0,
    status         VARCHAR(20)   NOT NULL,
    status_message VARCHAR(1000),
    indexed_at     TIMESTAMP,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE memory_fact (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    content           VARCHAR(2000) NOT NULL,
    source_message_id BIGINT,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Schema aus spring-ai-model-chat-memory-repository-jdbc 2.0.1 (schema-h2.sql)
CREATE TABLE SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36)  NOT NULL,
    content         LONGVARCHAR  NOT NULL,
    type            VARCHAR(10)  NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    timestamp       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    sequence_id     BIGINT       NOT NULL
);
CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX ON SPRING_AI_CHAT_MEMORY (conversation_id, timestamp DESC);
CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX ON SPRING_AI_CHAT_MEMORY (conversation_id, sequence_id);
