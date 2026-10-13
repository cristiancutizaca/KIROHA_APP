# Kiroha Sync Server v0.1

Servidor de referencia ejecutable para Sync multidispositivo. Usa únicamente la biblioteca estándar de Python y SQLite; no depende de Firebase ni de credenciales externas.

## Ejecutar

```bash
cd kiroha-sync-server
python3 server.py --host 0.0.0.0 --port 8787 --db kiroha-sync.sqlite3
```

El servidor escucha por defecto en `http://127.0.0.1:8787`.

## Flujo de vinculación

1. `POST /sync-spaces` con nombre y datos del dispositivo creador.
2. El servidor devuelve `spaceId`, `pairingCode` temporal y `deviceToken` del creador.
3. El creador llama `POST /sync-spaces/{spaceId}/pairing` para cada nuevo dispositivo.
4. El dispositivo nuevo llama `POST /sync-spaces/{spaceId}/join` con el pairing code y sus datos.
5. El dispositivo recibe un `deviceToken` individual; debe enviarlo como `Authorization: Bearer ...`.

## Operaciones

- `POST /sync/events` publica un evento idempotente.
- `GET /sync/events?spaceId=...&afterRevision=N` descarga eventos incrementales.
- `POST /sync/ack` confirma el cursor del dispositivo.
- `GET /devices?spaceId=...` consulta dispositivos registrados.
- `POST /devices/heartbeat` actualiza `lastSeen`.
- `POST /devices/revoke` revoca únicamente el dispositivo indicado.

Los pairing codes son aleatorios, expiran por defecto en 5 minutos, son de un solo uso y tienen rate limit por IP. Un token válido de otro espacio recibe `403`; no se entregan datos del espacio solicitado. Un token revocado recibe `401`.

Los eventos tienen identidad estable (`eventId`), revisión global monotónica y `originDeviceId`. SQLite impone unicidad por `(spaceId, eventId)`, por lo que reintentar un POST no genera duplicados.

## Prueba E2E

```bash
cd kiroha-sync-server
python3 test_e2e.py
```

La prueba levanta el servidor real en un proceso separado y usa HTTP real, autenticación, cuatro dispositivos, un cliente offline, ACKs, cursors e idempotencia.

## Limitaciones v0.1

- El pairing code se entrega directamente y todavía no existe QR.
- La revocación está disponible mediante API; falta una pantalla dedicada para revocar dispositivos individuales.
- Para Internet se requiere TLS mediante reverse proxy o despliegue HTTPS; no se deben exponer pairing codes en texto plano fuera de una red de confianza.
