# Android Sync v0.1

## Componentes

- `AndroidHttpSyncBackend`: implementa el protocolo HTTP real del servidor de referencia.
- `KirohaSyncJob`: `CoroutineWorker` con red requerida, retry exponencial y cursor persistente.
- `KirohaSyncConfig`: guarda URL y `spaceId`; cifra el device token con Android Keystore AES-GCM.
- `KirohaDatabaseRepository.insertRemoteEvent`: registra eventos remotos como acknowledged para que no vuelvan a la cola local.

## Flujo

```text
LocalSyncJournal.pending()
        → POST /sync/events
        → acknowledge local después de respuesta HTTP
        → GET /sync/events?afterRevision=cursor
        → insertRemoteEvent (sin republicar)
        → persistir cursor SQLDelight
        → POST /sync/ack + heartbeat
```

El Worker se programa al iniciar la app únicamente si existe una configuración válida. También se puede activar manualmente llamando `KirohaSyncJob.enqueue(context)` después de configurar:

```kotlin
KirohaSyncConfig.save(context, serverUrl, spaceId, deviceToken)
KirohaSyncJob.enqueue(context)
```

Si el servidor no está disponible, el journal local no se borra y WorkManager reintenta con backoff. La compilación está validada; el flujo físico Android ↔ PC todavía requiere un dispositivo/emulador y configuración de red real.
