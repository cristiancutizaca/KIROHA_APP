# Kiroha v0.1 — Candidate stabilization, aún NO certificada

**Rama principal: `main`. Historial secundario fusionado con `main` como base y conflictos revisados.** La secundaria `fix/kiroha-v01-p0-reliability` permanece intacta; existe respaldo `backup/main-before-combine-20261013`.

## Implementación del código (no equivale a pruebas)

- Identidad Android y SQLDelight async preservados.
- Cola de manga/anime aislada, migración legado, pausa y orden persistidos, restauración no destructiva, arranque/reintento por WorkManager.
- Descarga por capítulos mantiene `.tmp`, reusa archivos finalizados y deja de releer la carpeta por página. Descargas simultáneas limitadas por preferencia/temperatura Android.
- Paginación manga/anime con dedup por URL y páginas intermedias, límite de fuente rota y precarga; menos suscripciones de BD por tarjeta invisible.
- Categorías cacheadas, diálogos lazy y escritura de favoritos antes de trackers.
- Reindexado diferido y manual, fingerprint coherente entre almacenamiento y registro; el registro de copia completa no se emite por trabajos aún pendientes.
- Resource vectors nuevos Kiroha para launcher normal, debug y round en Android moderno; versionCode 144.
- Diagnóstico DEBUG sin secretos de Browse/background y protección de error de copia mpv.

## Situación de compilación

- `./gradlew` **NO pudo ejecutarse en el entorno de esta conversación**: el runtime de ejecución local devolvió errores.
- GitHub Actions CI y Kiroha Android debug APK reportaron **failure antes del primer paso** (0 steps) para el HEAD comprobado; no muestran error de compilación Gradle.
- Resultado: **BUILD PENDING / HARDWARE TEST PENDING**. No hay APK nueva verificable ni benchmark real.

## Validación por realizar

1. `spotlessCheck`, `:kiroha-core:test`, `testDebugUnitTest`, migraciones, ARM64/Universal build y validación de instalación/firmas.
2. Reproducir fuente → botón Inicio → regreso y capturar logcat. Comprobar cola antes y después con 10/100/500/1000 trabajos.
3. Cortar procesos/red a mitad de manga con 97/100 páginas, comprobar que conserva archivos y bytes parciales y reinicia trabajo según pausa.
4. Manga y Anime Browse profundo (20–60 páginas) contra Tail con la misma extensión, red y filtros; medir tiempos y duplicados.
5. Agregar 100 entradas a Biblioteca grande y abrir categorías repetidamente; comparar tiempos inicial/final, memoria y observers.
6. Medir comportamiento térmico y CPU en celular bajo Browse + descargas simultáneas.
7. Probar SAF/microSD y reindexado real de archivos Tail/Mihon existentes sin indices previos (funcionalidad incompleta).
8. Probar SyncSpace con múltiples dispositivos y sin mezclar identidades.
9. Comprobar launcher en instalador/Android y que actualización firmada con misma clave no obliga a desinstalar.

## Pendientes reales

Manifest granular de todos los estados/reintentos, descubrimiento de archivos no indexados, coordinador global de jobs, pruebas de regresión Android y rendimiento reproducible, branding PNG antiguo/asset final elegido, pruebas de SyncSpace multi-device, firma estable y APK release certificada.

**No declarar RC definitiva ni subir tag de versión final antes de pasar estas pruebas.**
