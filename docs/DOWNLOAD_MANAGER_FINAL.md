# Download Manager — estado final

Manga y anime conservan los stores maduros existentes (`MangaDownloadStore` y `AnimeDownloadStore`) en SharedPreferences. Ambos serializan IDs de item y orden, restauran la cola al iniciar y vuelven a insertarla en el downloader.

El recorder Kiroha observa los estados y persiste `DownloadRecord` con identidad global, storage, ruta relativa, tamaño, fingerprint, tiempos y `downloadedEver`.

Pause/resume, retry con backoff, cancelación y límites por fuente siguen siendo responsabilidad del motor heredado, que no fue reemplazado. El siguiente incremento razonable es añadir un manifest por archivo/página para reanudar una descarga parcialmente escrita sin redescargar unidades ya completas.

La prioridad de Reader/Player sobre mantenimiento debe mantenerse en cualquier integración futura: reindexado y fingerprint se ejecutan en `Dispatchers.IO` y nunca durante la composición UI.
