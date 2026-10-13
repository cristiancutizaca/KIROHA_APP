# Kiroha v0.1 — Project Status (fixed final)

Actualizado: 2026-10-07

## Implemented + tested

- SyncSpace privado y aislamiento backend; Sync server E2E.
- Device Registry, DownloadIndex, StorageReindexer e índice portátil.
- DownloadRecord global con identidad canónica, múltiples copias, `downloadedEver`, `MISSING`, `DELETED` y `STORAGE_UNAVAILABLE`.
- Eventos `DOWNLOAD` con metadata global sin rutas privadas.
- Identidad cross-device para manga/anime por `source + URL canónica`.
- Reader progress/read y Player `VIDEO_POSITION` con timestamps, seen/completed y checkpoints coalescidos.
- Filtros Core de local/remoto/offline/history/read/unread.
- Diagnóstico Core sanitizado.
- Tests Core de identidad, filtros, reindexado, journal, copias múltiples, borrado y descarga remota.

## Implemented + compiled

- Migración de lecturas Kiroha SQLDelight a driver async: `awaitAsList()` y `awaitAsOneOrNull()`; no quedan `executeAsList/executeAsOne/executeAsOneOrNull` en la ruta Kiroha Android.
- Sync Worker combina pending SQLDelight con journal legacy y hace acknowledge en ambas fuentes durante la migración.
- `storageId` derivado del URI/path real; no se asume `internal`.
- Reader resuelve desde cualquier storage seleccionado por Android.
- Scheduler automático debounceado y fallback periódico WorkManager.
- Deduplicación de paginación manga y anime por URL canónica; corta tras dos páginas consecutivas solo-duplicadas.
- Búsqueda global manga/anime deduplicada antes de convertir/guardar.
- Logo oficial adjunto integrado en legacy icon, adaptive foreground/background, monochrome y splash.
- APK ARM64 y Universal generadas desde clean build.

## Hardware test pending

- Instalar APK ARM64 en Android real y confirmar arranque sin crash.
- Scroll profundo de Popular/Latest/Search y verificación visual de no repetición.
- SAF/microSD real, extracción/reinserción y permisos revocados.
- Sync LAN entre POCO M8 y POCO principal.
- Restauración física de Player y mediciones de batería/rendimiento.

## Pending / partial

- Conexión visual completa de filtros/badges dentro de todas las pantallas de Library.
- Panel DEBUG Android conectado a todos los timings.
- Manifest fino de descargas por página y snapshot/compaction del servidor.
- Integración completa de metadata Kiroha en el backup existente.

## Validación sandbox

- `./gradlew clean :kiroha-core:test :data:compileDebugKotlin :app:assembleDebug :app:packageDebugUniversalApk --no-daemon`: SUCCESS.
- Core tests: PASS.
- Sync server E2E: PASS.
- ABI inspection: PASS; universal contiene arm64-v8a, armeabi-v7a, x86 y x86_64.
