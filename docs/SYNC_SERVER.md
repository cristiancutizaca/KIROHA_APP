# Kiroha Sync Server

El backend de referencia vive en `kiroha-sync-server/`. Es un servidor HTTP real con SQLite, independiente de Android y Firebase.

La prueba `python3 kiroha-sync-server/test_e2e.py` valida creación de espacio, unión de cuatro dispositivos, bearer tokens, publicación, GET incremental, ACK, dispositivo offline, revisiones globales e idempotencia.

La integración Android debe apuntar a la URL configurable del servidor, nunca a `localhost` hardcodeado. El cliente debe conservar eventos pendientes si el servidor está caído y reintentarlos con backoff. El backend no recibe secretos de Firebase y no contiene credenciales dentro del repositorio.
