# Kiroha v0.1 — Problemas conocidos y validación

Actualizado: 13/10/2026. **Rama de trabajo: `main`. Cambios de fuente aplicados, compilación nueva y pruebas físicas PENDIENTES.**

## Reportado por pruebas reales antes de estos cambios

- Desde Biblioteca → Inicio Android, la app quedaba abierta; desde una extensión/Browse → Inicio Android, el proceso podía cerrarse inesperadamente.
- Tras el cierre, la cola de pendientes de manga/anime desaparecía; las descargas completadas sí sobrevivían.
- Las páginas profundas de Browse/Search tardaban minutos, o parecían llegar al final sin agotar la fuente.
- Al agregar muchas entradas a Biblioteca, las categorías y el diálogo se volvían lentos.
- Descargas y navegación simultáneas causaban calor y consumo elevado en el teléfono.
- La APK instalada aún mostraba el logotipo de Animetail. En ocasiones fue necesario desinstalarla para actualizar.

## Modificaciones de código incorporadas a main

- **Cola:** SharedPreferences separadas para manga/anime, recuperación sin vaciar almacenamiento, reordenamiento con escritura atómica, pausa persistida, workers esperando restauración y reanudación automática únicamente de trabajo no pausado; constraints de red y reintentos.
- **Capítulos incompletos:** preservación de archivos `.tmp` y HTTP Range; reutilización de páginas completas sin pedir su URL otra vez; índice de archivos existentes por capítulo para eliminar recorridos O(n²) del directorio SAF.
- **Rendimiento:** límite global de descargas de manga, menos peticiones de imágenes simultáneas, reducción por estado térmico del sistema; precarga Browse a ~12 entradas del final, suscripciones DB inactivas para tarjetas no visibles; caché observable de categorías y escritura secuencial antes de vincular trackers.
- **Paginación:** manga/anime saltan páginas vacías y duplicadas dentro de un presupuesto finito; si la fuente no progresa, reportan error recuperable en vez de fin falso; URL canónica para identidad.
- **Arranque:** reindexado automático diferido y limitado en frecuencia, omite reindexado concurrente a descargas; manejo de errores de indexado y registradores. Los cálculos de huella de los archivos descargados y su reindexado usan ahora el mismo algoritmo.
- **UI:** acción «Reindexar descargas» en menús de Biblioteca de manga y anime; listas de categorías perezosas con IDs estables.
- **Crash/branding:** fallos de copia mpv aislados; registros privados mínimos de actividad en DEBUG (tipo de Browse, momento de segundo plano, memoria utilizada), sin URLs/tokens; nuevos recursos vectoriales Kiroha para iconos adaptativos en Android moderno.
- **Actualización:** versionCode 144. El `applicationId` sigue siendo `app.kiroha.mobile`; DEBUG usa `.dev`.


## Corrección propuesta en rama `fix/download-identity-by-source-and-url-20261014`

- Un nombre/título NO identifica una obra. Las nuevas rutas de manga se separan por ID de fuente + URL canónica de manga, y los capítulos se guardan siempre con hash de URL, incluso si antes estaba deshabilitada esa opción.
- Se modificó el caché y los indicadores de lectura/biblioteca/actualizaciones para usar esa identidad. El identificador del progreso Kiroha incluye tanto URL de obra como de capítulo para evitar colisiones entre obras con capítulos relativos idénticos.
- **Migración pendiente por seguridad:** las carpetas antiguas `Fuente/Título/Capítulo` o sin hash de URL se conservan en disco, pero no se adjudican automáticamente a una obra. En títulos repetidos no hay manera segura de saber a cuál pertenecen. Por tanto las descargas anteriores pueden mostrarse temporalmente como no descargadas hasta una migración explícita y verificada. **No borrar carpetas antiguas ni reinstalar sin respaldo.**
- La corrección está en rama de revisión, **NO equivale a APK probada**. Falta compilar, ejecutar tests y probar dos galerías de mismo título con diferente cantidad de páginas en teléfonos reales. Los checks CI están pendientes.

## NO está demostrado / pendiente

- **La causa exacta del cierre desde extensiones NO ha sido demostrada ni corregida con certeza.** Requiere logcat del teléfono y repetición con una APK compilada.
- No hay build exitoso de la revisión actual. GitHub Actions falla antes de iniciar sus pasos (sin logs de Gradle); no existe APK actual validada.
- No se ha comprobado recuperación real de 10, 100, 500 o 1000 pendientes, pausas, errores, microSD retirada, ni descarga de 97/100 páginas.
- Solo está integrado el manejo heredado de páginas y reanudación por `.tmp`; faltan estados individuales y manifest persistente completo para toda cola.
- Reindexar comprueba registros/índices conocidos, pero **no descubre de forma general todos los archivos no indexados de Tail/Mihon/Animetail**. La compatibilidad con carpetas y permisos reales requiere integración adicional.
- No hay certificación térmica de dispositivo, comparaciones cronometradas con Tail, ni pruebas de scroll de 2000+ elementos ni 100 incorporaciones.
- No hay prueba SyncSpace de 2+ dispositivos reales, retirada de microSD o validación completa de Player/Reader.
- El icono vectorial de Android está integrado para los iconos adaptativos. Los PNG de compatibilidad antigua y la sustitución exacta del diseño elegido anteriormente siguen pendientes.
- Las APK DEBUG generadas con claves diferentes pueden requerir desinstalar; `versionCode` mayor por sí solo no soluciona firmas distintas. Para actualizar sin perder datos, firmar con la **misma clave** que la instalación anterior.
- Los filtros visuales completos de DownloadFilterEngine, copias de seguridad Kiroha, coordinador global, empaquetado release firmado y exportación de diagnósticos permanecen pendientes.

**No etiquetar v0.1 FINAL ni presentar «PASÓ EN HARDWARE» hasta completar la compilación y las pruebas.**
