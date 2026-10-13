# Kiroha v0.1 — Known Issues

## Correcciones P0/P1 del 13/10/2026 (sin validación física todavía)

- **Colas:** manga y anime ya no comparten `active_downloads`. Se separaron los almacenes, se agregó migración de entradas anteriores, y la restauración ya no vacía el almacén antes de completar. El worker espera a la restauración antes de iniciar.
- **Paginación:** manga y anime avanzan por páginas intermedias vacías o duplicadas con un límite de seguridad de seis páginas. La deduplicación sigue basada en URL, no título.
- **Browse:** las consultas individuales de los resultados dejan de coleccionarse permanentemente cuando no tienen suscriptores visibles. El rendimiento en fuentes reales, especialmente e-hentai, sigue pendiente de medir.
- **Descargas/temperatura:** se corrigió el ciclo de espera de los workers, se evita reemplazar el worker activo y se impone un límite global de capítulos simultáneos; los videos activos bajaron a dos. El comportamiento térmico del teléfono aún requiere validación.
- **Reindexado:** se añadió `Reindexar descargas` al menú de Biblioteca y se evita repetir el reindexado automático completo durante 12 horas después de terminar uno, salvo orden manual.
- **Cierre al volver al inicio desde una extensión:** confirmado por el usuario en Android, pero la causa exacta no está aislada. Revisar logcat/CrashActivity al reproducirse; **no marcar como resuelto** por los cambios de concurrencia.
- **Compilación:** GitHub Actions falló antes de ejecutar sus pasos en las ejecuciones observadas el 13/10. No se verificó la compilación ni se generó APK a partir de estos cambios. No asumir que el código compila hasta pasar CI/Gradle.

- No hay teléfono, ADB, emulador ni microSD disponibles en el sandbox. SAF, retirada/reinserción y cambio A23 → M8 requieren validación física.
- El índice portátil es un acelerador, no una autoridad. La autoridad es la existencia y fingerprint verificados por `StorageProvider`.
- La UI de biblioteca todavía usa filtros maduros de Mihon/Animetail; `DownloadFilterEngine` ya está en Core, pero sus chips visuales Kiroha quedan pendientes.
- El manifest por página/archivo del Download Manager no sustituye la persistencia de cola existente; la cola se restaura, pero la reanudación fina de una descarga parcial requiere integración adicional.
- El Player y Reader aplican progreso remoto por ID local. Si un contenido no existe en el dispositivo receptor, el evento queda registrado sin crear contenido inexistente.
- La prueba de servidor E2E debe ejecutarse directamente con `python3 kiroha-sync-server/test_e2e.py`; `unittest discover` no descubre ese archivo por su nombre actual.
- Las advertencias Gradle/Kotlin existentes son deprecaciones y código heredado de Animetail/Mihon; no bloquean el build.
