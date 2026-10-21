# 0003 – Embeddings immer über `bge-m3` auf Ollama

- **Status:** angenommen
- **Datum:** 2026-09-30

## Kontext

Mit [0002](0002-umschaltbarer-chat-provider.md) kann der Chat-Provider wechseln. Anthropic bietet keine
Embedding-API. Würde das Embedding-Modell mitwechseln, passen gespeicherte Vektoren (Dimension und
Vektorraum) nicht mehr zu neuen Anfragen.

## Entscheidung

Das einzige `EmbeddingModel` ist `bge-m3` auf dem Ollama-Server – unabhängig vom aktiven Chat-Provider.
Dokumente, Fakten und Suchanfragen werden immer damit eingebettet.

## Konsequenzen

- RAG und Fakten-Gedächtnis funktionieren auch mit Claude.
- Auch im Claude-Modus ist der LLM-Server für Wissen/Gedächtnis nötig. Ist er weg, antwortet Claude
  ohne RAG-Kontext; das wird als Warnung im Chat angezeigt, nicht still verschluckt.
- Ein späterer Wechsel des Embedding-Modells erfordert eine Neu-Indizierung aller Dokumente und Fakten.
