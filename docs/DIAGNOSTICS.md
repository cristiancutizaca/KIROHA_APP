# Diagnostics

`kiroha-core` incluye `KirohaDiagnostics`, que registra timings de operaciones y exporta texto sanitizado.

El export solo incluye:

- device y SyncSpace abreviados;
- número de eventos pendientes;
- estado por storage;
- operación, duración y resultado.

Nunca exporta bearer tokens, pairing codes, rutas privadas remotas ni secretos. El código está preparado para conectarse a una pantalla DEBUG de diagnóstico sin obligar a cargar datos masivos en la UI.

Operaciones recomendadas para instrumentar:

- `reader/openRequested`, `indexResolved`, `storageResolved`, `firstFileOpened`, `firstPageReady`;
- `player/open`, `mediaResolved`, `resumePositionLoaded`, `playbackReady`;
- `download/queue`, `download/retry`, `download/throughput`;
- `storage/reindex` y `sync/apply`.
