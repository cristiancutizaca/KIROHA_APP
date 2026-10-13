# Kiroha Core v0.1

## Estado

**Implementado + probado:** Device Registry persistente mediante `KeyValueStore`, estados global/local/histórico de descargas y resolución de conflictos de progreso.

## Decisiones

- `deviceId` es aleatorio, persistente y no depende del modelo del teléfono.
- La plataforma está representada por `ANDROID`, `WINDOWS` y `LINUX` para evitar reescrituras del protocolo.
- `AVAILABLE_LOCAL` es la única disponibilidad accesible inmediatamente en el dispositivo actual.
- `LAST_KNOWN_REMOTE` y `STORAGE_UNAVAILABLE` conservan la descarga global aunque el dispositivo esté offline o la microSD esté retirada.
- `DELETED` deja `downloadedEver=true`, pero ya no cuenta como descarga actual salvo que exista otra copia.
- El progreso de manga resuelve conflictos con la página más avanzada; el video usa el evento más reciente.

## Integración pendiente

El siguiente paso es implementar el adaptador Android de `KeyValueStore` sobre DataStore/SQLDelight y registrar los `DownloadRecord` en la base de datos existente. El módulo puro ya está en el grafo de build y sus pruebas no dependen de Android.
