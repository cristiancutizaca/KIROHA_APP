# Kiroha v0.1 — Known Issues

- No hay teléfono, ADB, emulador ni microSD disponibles en el sandbox. SAF, retirada/reinserción y cambio A23 → M8 requieren validación física.
- El índice portátil es un acelerador, no una autoridad. La autoridad es la existencia y fingerprint verificados por `StorageProvider`.
- La UI de biblioteca todavía usa filtros maduros de Mihon/Animetail; `DownloadFilterEngine` ya está en Core, pero sus chips visuales Kiroha quedan pendientes.
- El manifest por página/archivo del Download Manager no sustituye la persistencia de cola existente; la cola se restaura, pero la reanudación fina de una descarga parcial requiere integración adicional.
- El Player y Reader aplican progreso remoto por ID local. Si un contenido no existe en el dispositivo receptor, el evento queda registrado sin crear contenido inexistente.
- La prueba de servidor E2E debe ejecutarse directamente con `python3 kiroha-sync-server/test_e2e.py`; `unittest discover` no descubre ese archivo por su nombre actual.
- Las advertencias Gradle/Kotlin existentes son deprecaciones y código heredado de Animetail/Mihon; no bloquean el build.
