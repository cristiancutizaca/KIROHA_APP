# KIROHA: revisión de rendimiento (15/10/2026)

## Alcance

Auditoría estática del flujo de descargas, índices SAF/almacenamiento, cola, ficha de manga y pantalla Más en `main`. Se revisaron las rutas de código y las modificaciones disponibles; **no se ejecutó una compilación Android ni mediciones instrumentadas de temperatura, CPU o memoria**. Este documento técnico no equivale a una validación de APK.

Respaldos: `backup/main-before-optimization-20261015`. Primera versión que agrupa estos cambios: **0.1.6 (versionCode 150)**.

## Hallazgos y mejoras aplicadas

| Prioridad | Cuello de botella | Acción |
|---|---|---|
| Alta | `MangaDownloadCache.getDownloadCount()` recorría todos los capítulos antiguos comprobados **para cada tarjeta de biblioteca**. Con N tarjetas y M verificaciones la carga podía crecer como O(N·M). | Conteos positivos de descargas heredadas por clave fuente/obra en `ConcurrentHashMap<LegacyMangaKey, AtomicInteger>`; la consulta es O(1). Se vacían con `invalidateCache`. |
| Alta | `MangaViewModel.toChapterListItems()` consultaba la cola mediante `getQueuedDownloadOrNull()` con búsqueda lineal para **cada capítulo**. | Un solo `associateBy { chapter.id }` por reconstrucción de lista; reduce de O(capítulos × cola) a O(capítulos + cola). |
| Alta | Reconstrucción de índices de manga y anime podía abrir muchas carpetas SAF de distintas fuentes **al mismo tiempo**, elevando latencia, I/O y consumo. | Indexación en lotes de 2 fuentes; se conserva el mismo resultado lógico. |
| Alta | Los canales `_changes` tenían capacidad `UNLIMITED` y se lanzaba otra coroutine por notificación. Una sucesión de cambios podía acumular emisiones y trabajos redundantes. | Canal `CONFLATED` y `trySend(Unit)`: la UI siempre vuelve a leer el estado más reciente. |
| Media | Los índices serializados se escribían directamente con `File.writeBytes`, pudiendo quedar truncados si Android mataba el proceso. | `android.util.AtomicFile` con `startWrite`, `finishWrite`, `failWrite` para preservar el índice anterior en caso de corte. |
| Media | Las reconstrucciones fallidas actualizaban `lastRenew` y evitaban otros intentos hasta **una hora**. | `lastRenew` solo queda fijado tras una indexación completada; errores/cancelaciones pueden reintentarse. |
| Media | `AnimeDownloadStore.restore()` resolvía anime/episodios con `runBlocking` individual, bloqueando el hilo restaurador. | `suspend fun restore()` + llamadas suspensivas directas al repositorio. |
| Media | `MangaDownloadManager.startDownloadNow()` hacía una consulta SQL suspensiva mediante `runBlocking` desde una acción de usuario. | Buscar capítulos en `Dispatchers.IO` y volver a `Dispatchers.Main.immediate` únicamente para reordenar y guardar la cola. |
| Media | `MoreViewModel` anidaba un `combine` dentro de otro; con cambios de manga cancelaba y re-suscribía innecesariamente los flujos de anime. | Un único `combine` de los cuatro `StateFlow` de colas y ejecución. |

## Por qué estos cambios son conservadores

- **No se han movido ni borrado descargas existentes.** Los formatos y rutas de archivos no se alteran.
- **No se cambió** la persistencia serializada de tareas en `SharedPreferences`, para no reintroducir la desaparición de la cola.
- **No se cambió** el esquema SQL ni la identidad de capítulos; se preservan las medidas contra mezcla de galerías con títulos iguales.
- **No se sustituyó** mpv, el visor de manga ni el lector de ficheros nativo.
- Las optimizaciones de escaneo SAF reducen picos de acceso; pueden hacer algo más lento un escaneo inicial en dispositivos muy rápidos, a cambio de reducir concurrencia y presión sobre microSD.

## Riesgos y límites todavía abiertos

1. **Recuperación de descargas antiguas:** reconocer todas las carpetas históricas requiere verificar identidad; la coincidencia visual de títulos no prueba pertenencia y **no debe automatizarse** sin datos confiables. La recuperación actual es parcial.
2. **Bibliotecas grandes:** queda por perfilar la cadena `MangaViewModel` / `downloadCache.changes` y la ejecución de consultas y filtros sobre 1.000–10.000 entradas.
3. **Descarga de video:** el proceso FFmpeg, los streams remotos y el almacenamiento SAF pueden consumir CPU/red; medir antes de cambiar su arquitectura.
4. **Reindexación cancelada:** comprobar que los mutex y los índices temporales siguen coherentes si se elimina la app del historial, se cambia el permiso SAF o la microSD se desmonta durante una exploración.
5. **Contadores:** los contadores heredados reflejan **capítulos verificados**; si un capítulo está en ubicaciones tanto nuevas como antiguas podrían darse dobles contabilizaciones, por resolver usando una identidad de capítulo común, sin depender del título.
6. **Rendimiento real:** verificar tiempos de inicio, abrir biblioteca, cambiar capítulos, 714+ carpetas de descarga, cola tras cerrar la aplicación, RAM, batería y temperatura.

## Verificación realizada y pendiente

- [x] Inspección de rutas afectadas y cambios sobre la rama principal.
- [x] Respaldo anterior.
- [x] Comparación de archivos modificados y comprobación textual de estructuras.
- [ ] Compilar Android `assembleDebug` / `assembleDebugUniversalApk`.
- [ ] Ejecutar `spotlessCheck`, tests JVM del módulo Core, instrumentación y pruebas con microSD.
- [ ] Perfilado comparativo con Perfetto/Android Studio: antes y después, mismos capítulos y dispositivo.
- [ ] Comprobar lectura de descargas antiguas, cola tras reinicio, historial, biblioteca y reproductor.

**Motivo de validación pendiente:** los workflows de GitHub Actions disponibles para la rama fallan antes de ofrecer logs de compilación utilizables. Este documento no presupone que la APK compile hasta verificarlo en un entorno Android correctamente configurado.
