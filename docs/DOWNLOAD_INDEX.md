# Kiroha Download Index

`DownloadIndex` es una proyección rápida de `DownloadRecord`. Agrupa por `itemId`, permite actualizar una copia individual y resuelve una copia utilizable sin escanear carpetas.

La prioridad de resolución es: copia local disponible en el dispositivo actual, copia remota disponible, última copia remota conocida y almacenamiento temporalmente ausente. Los registros `MISSING` y `DELETED` no se devuelven como una ruta utilizable, pero se conservan para historial e inventario global.

La base SQLDelight ya tiene un índice por `item_id`; el repositorio consulta únicamente los registros del elemento solicitado. El siguiente paso es conectar `localPath`, tamaño y checksum desde los providers reales y añadir reindexado incremental por almacenamiento.
