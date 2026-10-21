# 0004 – `SimpleVectorStore` statt Azure AI Search

- **Status:** angenommen
- **Datum:** 2026-09-30

## Kontext

Das per start.spring.io generierte `pom.xml` enthält `spring-ai-starter-vector-store-azure`.
Das ist ein Cloud-Dienst mit Account und Key und widerspricht der Entscheidung, Wissen lokal zu halten.
Die README sah von Anfang an `SimpleVectorStore` vor.

## Entscheidung

- `spring-ai-starter-vector-store-azure` wird aus dem `pom.xml` entfernt.
- Verwendet wird `SimpleVectorStore` (In-Memory, persistiert als JSON-Datei unter
  `jarvis.knowledge.vector-store-file`), als Bean in `knowledge/VectorStoreConfig` gebaut.
- Die Datei wird beim Start geladen und nach jeder Änderung (Ingestion, Fakt hinzufügen/löschen) gespeichert.

## Konsequenzen

- Reicht für einige hundert Chunks. Upgrade-Pfad bleibt pgvector/Qdrant – nur `VectorStoreConfig` ändert sich,
  da alle Aufrufer gegen das `VectorStore`-Interface arbeiten.
- Die Vektoren liegen lokal auf dem PC; nur zum Einbetten wird der LLM-Server angesprochen.
