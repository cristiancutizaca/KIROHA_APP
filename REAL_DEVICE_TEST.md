# Kiroha v0.1 — Real Device Test

Este checklist requiere hardware Android real y queda marcado como `HARDWARE TEST PENDING` en el sandbox.

## Storage elegido por Android

1. En POCO M8, seleccionar la carpeta de descargas mediante el selector normal de Animetail/Mihon. La carpeta puede estar en almacenamiento interno, microSD u otro árbol SAF permitido.
2. Descargar un manga y un episodio de anime.
3. Confirmar que Kiroha detecta el root real, genera un `storageId` canónico y no crea una segunda identidad para el mismo URI.
4. Cerrar y reabrir la app; verificar que el reindexado conserva `AVAILABLE_LOCAL`.
5. Cambiar la carpeta de descargas y verificar que las descargas nuevas quedan asociadas al nuevo root, sin asumir `internal`.

## Download Sync automático

1. Configurar el POCO M8 y el POCO principal en el mismo SyncSpace privado.
2. Descargar X en M8 y esperar el debounce de Sync sin pulsar `Sync now`.
3. En el POCO principal, abrir biblioteca y comprobar `REMOTE`, `downloadedGlobal=true`, `downloadedHere=false` y que X no aparece en `NOT_DOWNLOADED`.
4. Detener temporalmente el servidor. Generar otro evento. Confirmar que permanece pending y que WorkManager reintenta al volver la red/servidor.
5. Con B y C manteniendo copias de X, borrar B. El estado global debe seguir descargado. Borrar C: `downloaded=false`, `downloadedEver=true`.

## Identidad canónica

1. Crear el mismo capítulo/episodio en dos dispositivos con IDs locales diferentes.
2. Leer en A y reproducir en B. Confirmar que el receptor localiza por source namespace + URL canónica, no por el ID numérico remoto.
3. Confirmar que Reader conserva `current` y `furthest`, y que releer una página anterior no borra el máximo histórico.
4. Confirmar que Player restaura timestamp y estado visto/completado.

## SAF/microSD

Seleccionar nuevamente el árbol SAF después de mover la tarjeta a otro teléfono. Kiroha debe reindexar archivos existentes y reconstruir records. Si Android revoca el acceso, el estado esperado es `STORAGE_UNAVAILABLE`, no `DELETED`; al restaurar el permiso debe volver a `AVAILABLE_LOCAL` sin redescargar.

## Diagnósticos y privacidad

Exportar el diagnóstico DEBUG y comprobar que solo contiene IDs abreviados, cursor, pending, estados y timings. No debe contener tokens, códigos pairing, URI SAF ni rutas privadas.

## Arranque, paginación y branding final

1. Instalar `Kiroha_v0.1_arm64-v8a_debug.apk`, abrir la app y confirmar que no reaparece el crash `The driver used with SQLDelight is asynchronous`.
2. Abrir Popular, Latest y Search; hacer scroll profundo y confirmar que una extensión que repite una página no produce una lista infinita.
3. Confirmar que dos obras con mismo título/autor pero URL distinta coexisten y que la misma URL repetida aparece una sola vez.
4. Confirmar que el launcher, icono redondo, themed icon y splash muestran el logo oficial Kiroha sin branding visible heredado.
5. Registrar dispositivo, Android, ABI, storage y logs sanitizados. No marcar una prueba como pasada sin hardware real.
