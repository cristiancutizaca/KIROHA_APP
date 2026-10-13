# Kiroha Sync Protocol v0.1 (estado actual)

## Principios

- Cada dispositivo escribe localmente sin bloquear Reader ni descargas.
- Los cambios se representan como eventos incrementales.
- Un transporte futuro drena eventos pendientes y confirma sus IDs.
- No se sincronizan archivos multimedia por defecto; se sincronizan metadatos y estados.

## Evento

`SyncEvent` contiene:

- `eventId` único;
- `entity` (`DEVICE`, `LIBRARY`, `PROGRESS`, `HISTORY`, `FAVORITE`, `CATEGORY`, `DOWNLOAD`);
- `entityId` estable;
- `operation` (`UPSERT`, `DELETE`, `HEARTBEAT`);
- `revision`, `timestamp`, `deviceId`;
- `payload` de metadatos.

`LocalSyncJournal` persiste eventos append-only y permite `pending()` y `acknowledge(eventIds)`.

## Resolución

- Página manga: se conserva la página más avanzada.
- Video: gana el evento válido más reciente.
- `downloadedEver=true` se conserva aunque la última copia se borre.
- Una copia remota offline permanece conocida; no se convierte automáticamente en no descargada.

## No implementado todavía

El transporte Android ↔ Android, la autenticación, el endpoint/backend y el repositorio SQLDelight de eventos todavía requieren implementación. No se deben introducir credenciales ni un servidor ficticio para marcarlo como terminado.

## Persistencia SQLDelight

Las migraciones 43 y 44 añaden `kiroha_sync_event`, `kiroha_sync_space` y `kiroha_sync_cursor` con índices para pendientes y entidad/revisión. El repositorio ya permite insertar, consultar pendientes y confirmar eventos; el journal SharedPreferences se mantiene como fallback de bootstrap hasta implementar migración explícita.


## Sync Space y cursors

`SyncSpace` identifica el espacio compartido del usuario. `SyncEnvelope` conserva la revisión global asignada por el backend, el `eventId` y `originDeviceId`. `SyncClient` solo solicita revisiones posteriores a su cursor y avanza ese cursor después de aplicar el lote. La aplicación de un evento remoto no lo vuelve a publicar, evitando loops.

La prueba multidispositivo simula cuatro clientes: B publica una descarga, A y C la reciben, A publica progreso leído, C lo recibe y D, que estaba offline, recibe ambos eventos al volver. Reenviar el mismo `eventId` es idempotente.
