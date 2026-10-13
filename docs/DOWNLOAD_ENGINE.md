# Kiroha Download Engine

## Integración actual

Kiroha conserva los gestores maduros de Animetail para manga y anime. En el proceso principal, `App` observa `MangaDownloadManager.statusFlow()` y `AnimeDownloadManager.statusFlow()` en `Dispatchers.IO`. Cada cambio real de estado se convierte en un `DownloadRecord` persistido por `KirohaDatabaseRepository`.

Los estados `QUEUE`, `DOWNLOADING` y `ERROR` se registran como una copia no disponible localmente. `DOWNLOADED` se registra como `AVAILABLE_LOCAL`, actualiza `lastVerifiedAt` y conserva `downloadedAt`. Si el registro anterior tenía `downloadedEver=true`, una transición posterior no lo pierde.

La identidad del contenido usa namespace y URL canónica: `manga:<source>:<chapter.url>` o `anime:<source>:<episode.url>`. Esto evita depender solamente del título y permite sincronizar el inventario entre dispositivos.

## Pendientes conocidos

La cola interna y WorkManager siguen siendo la fuente de verdad del motor existente. Falta conectar de forma explícita el borrado físico de capítulos/episodios para producir `DELETED`, obtener `localPath`, tamaño y checksum desde cada provider, y persistir una cola recuperable propia. No se reemplaza el gestor maduro antes de medir una necesidad real.

## Próximo bloque

Añadir un `DownloadIndex` consultable por `itemId`, conectar los callbacks de borrado/reindexado y crear una prueba de ciclo `DOWNLOADED → DELETED` que preserve `downloadedEver`.
