# Kiroha v0.1 — Roadmap final

## Implementado en esta ejecución

Se mantienen el Device Registry, journal local-first, SyncSpace privado, backend HTTP/SQLite, pairing, tokens individuales, revocación, cursores e idempotencia. También quedan implementados `DownloadRecord`, `DownloadIndex`, estados globales, Storage interno/SAF, fingerprints, índice portátil, reindexado por item/serie/storage/all, reconstrucción desde el índice, persistencia batch transaccional, Reader indexado, progreso Reader `MANGA_PAGE`, progreso Player `VIDEO_POSITION` con checkpoints locales, aplicación de progreso remoto, filtros Core sin filesystem scan y diagnósticos sanitizados.

## Pendiente real

1. Conectar `DownloadFilterEngine` a los componentes visuales concretos de las pestañas de biblioteca y añadir badges LOCAL/REMOTE/OFFLINE/HISTORY.
2. Mostrar detalles de copias en pantallas de manga/anime sin revelar rutas remotas.
3. Añadir eventos Sync para favoritos, categorías, historial y cambios de biblioteca sin duplicar repositorios.
4. Añadir manifest por archivo/página al Download Manager para reanudar unidades parciales dentro del motor existente.
5. Ejecutar pruebas físicas en POCO principal, POCO M8 con microSD y tercer Android.
6. Medir rendimiento real con datasets de 1.000, 5.000 y 10.000 entradas.
7. Firmar release y validar instalación limpia/upgrade en hardware.

El backend, Reader, Player y motor de descarga maduros no se reescriben. Las limitaciones de hardware permanecen documentadas como `IMPLEMENTADO / HARDWARE TEST PENDING`.
