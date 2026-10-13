

# Kiroha Storage and Download Index

## Estado implementado

- `StorageProvider` separa resolución, lectura, escritura, borrado y fingerprint del resto de la app.

- `InternalStorageProvider` usa `java.nio.file.Path` y rechaza rutas absolutas o traversal.

- `SafStorageProvider` usa Android Storage Access Framework, URI persistente y múltiples storages.

- `SafStorageRegistry` conserva IDs estables derivados del URI y no almacena secretos.

- La selección actual de carpeta en **Settings → Data and storage** registra también el storage SAF de Kiroha.

- `DownloadReconciler` distingue:
  - `AVAILABLE_LOCAL`: archivo encontrado y fingerprint válido;
  - `MISSING`: storage disponible, pero archivo ausente;
  - `STORAGE_UNAVAILABLE`: árbol de storage no accesible;
  - `ERROR`: archivo presente pero cambiado;
  - `DELETED`: borrado confirmado, no se crea automáticamente durante verificación.

- `DownloadIndex` responde consultas local/remota/global sin recorrer filesystem durante la composición UI.

- `DownloadPageLoader` consulta primero el `localPath` verificado de Kiroha para resolver el capítulo en tiempo constante respecto al índice; conserva `MangaDownloadProvider` como fallback compatible con instalaciones antiguas o registros aún no indexados.

- El Reader conserva la actualización normal de Mihon y además emite eventos `PROGRESS` (`MANGA_PAGE`) al `LocalSyncJournal`, identificados por capítulo y dispositivo para sincronización incremental posterior.

- El Player emite `VIDEO_POSITION` local-first cada cinco segundos como máximo y al salir/cambiar de episodio; el Sync Worker aplica los eventos remotos al episodio local cuando existe.

- `DownloadFilterEngine` calcula ALL, DOWNLOADED, NOT_DOWNLOADED, AVAILABLE_HERE, AVAILABLE_REMOTE, OFFLINE y DOWNLOADED_EVER exclusivamente desde `DownloadIndex`, permitiendo combinar además el estado de lectura.

- El `storageId` local se deriva del URI/path del root de descargas elegido por Android. El recorder y el provider de reindexado comparten esa identidad, y los providers se deduplican por ella. Las rutas/URI no se incluyen en eventos remotos.

- Los cambios productivos de descarga emiten eventos `DOWNLOAD` coalescidos. El receptor conserva solo `itemId`, device, label lógico, estado, tamaño, fingerprint temporal y `downloadedEver`; nunca importa `localPath` remoto.

- `PortableStorageIndex` escribe `.kiroha/index` sin tokens ni credenciales. Es una caché aceleradora, nunca autoridad absoluta.

## Fingerprint

Se calcula SHA-256 de metadata (`size`, `lastModified`) y como máximo 64 KiB iniciales + 64 KiB finales. No se leen gigabytes completos para cada verificación.

## Limitaciones actuales

- El recorder Android ya conecta rutas físicas, tamaño y fingerprint para manga y anime. Sigue pendiente un manifest fino por página/archivo para reanudar unidades parciales.

- La prueba SAF y retirada/reinserción de microSD queda pendiente de hardware Android.

- Al arrancar la aplicación, `StorageReindexer` reconstruye los registros de cada storage disponible desde `.kiroha/index` y reconcilia fingerprints.
- Un índice ausente o corrupto no convierte copias en `DELETED`: se conservan los registros existentes y solo se reconstruye el acelerador cuando hay datos verificables.
- Si se retira una microSD, las copias pasan a `STORAGE_UNAVAILABLE` y conservan `downloadedEver`, `localPath` y checksum para recuperarse al reinsertarla.

- El reindexado Android persiste el resultado con `upsertDownloads` dentro de una transacción SQLDelight, evitando miles de commits individuales.

- El Reader/Player programa un único WorkManager Sync con debounce. Existe además un fallback periódico de seis horas con red disponible; servidor offline conserva eventos pendientes y usa retry exponencial.
