-- Einstellungen des Assistenten (Hiebler). Singleton: genau eine Zeile mit id = 1,
-- wird beim ersten Zugriff von SettingsService mit den Defaults aus der Konfiguration angelegt.

CREATE TABLE assistant_settings (
    id            BIGINT        PRIMARY KEY,
    provider      VARCHAR(20)   NOT NULL,
    model         VARCHAR(100)  NOT NULL,
    system_prompt VARCHAR(4000) NOT NULL,
    temperature   DOUBLE        NOT NULL,
    think_mode    BOOLEAN       NOT NULL DEFAULT FALSE,
    voice_de      VARCHAR(100),
    voice_en      VARCHAR(100),
    tts_enabled   BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
