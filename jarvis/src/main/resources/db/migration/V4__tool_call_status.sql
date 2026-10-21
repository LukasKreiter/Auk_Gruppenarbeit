-- Ausgang eines Tool-Aufrufs (OK, FAILED, NOT_EXECUTED), damit Fehler und abgelehnte Aufrufe von Erfolgen
-- unterscheidbar sind. Bestehende Zeilen gelten als OK (vorher nicht unterschieden).
ALTER TABLE tool_call ADD COLUMN status VARCHAR(20) DEFAULT 'OK' NOT NULL;
