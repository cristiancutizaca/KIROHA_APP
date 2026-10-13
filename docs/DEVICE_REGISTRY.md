# Device Registry Android

## Implementación actual

`kiroha-core` expone `DeviceRegistry` y Android lo usa desde `App.onCreate` mediante:

- `AndroidKeyValueStore` sobre `SharedPreferences` privadas de la aplicación.
- `AndroidDeviceInfo.name(context)` para obtener nombre configurable o fabricante/modelo.
- `BuildConfig.VERSION_NAME` como versión reportada.
- `Platform.ANDROID` como plataforma actual.

El identificador es generado aleatoriamente, persistido bajo `kiroha.device.registry.v1` y reutilizado después de cerrar, reiniciar o actualizar la aplicación mientras se conserve el almacenamiento privado.

## Presencia

El proceso principal registra un heartbeat local en `LocalSyncJournal` con `entity=DEVICE`, `operation=HEARTBEAT`, `deviceId`, timestamp y versión. Esto no necesita red y queda disponible para un transporte posterior.

## Migración siguiente

Mover el registro y el journal a SQLDelight para permitir consultas, índices, transacciones, retención de eventos y sincronización incremental. El adaptador SharedPreferences deberá conservarse como fallback de migración para no perder el deviceId existente.

## Persistencia SQLDelight

La migración 43 añade `kiroha_device`, y `KirohaDatabaseRepository` escribe el mismo dispositivo en `tachiyomi.db` durante el arranque. SharedPreferences sigue siendo el origen de bootstrap para conservar el identificador antes de completar una migración de datos existente.
