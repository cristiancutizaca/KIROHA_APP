# KIROHA 0.1.3 / 147: cola persistente y recuperación de archivos heredados

## Hallazgos corregidos en código

### Cola desaparece al cerrar KIROHA

Las implementaciones `MangaDownloader` y `AnimeDownloader` llamaban a `store.restore()` inmediatamente durante el inicio. Sin embargo, los gestores de fuentes cargan las extensiones **asíncronamente**. Antes de estar listos, `sourceManager.get(sourceId)` devolvía null, y `restore()` omitía las entradas existentes de esa fuente. Los registros persistidos quedan en preferencias, pero la cola visible reaparecía vacía.

La restauración **ahora espera `sourceManager.isInitialized.first { it }`** antes de leer las colas y resolver los IDs de fuentes. El inicio automático de WorkManager ya esperaba a la restauración, por lo que no debería arrancar con una cola provisional vacía.

**Límite:** si la extensión correspondiente fue desinstalada, sigue siendo imposible convertir esos registros de preferencias en descargas activas. Esos registros NO se borran, y podrán reaparecer cuando se instale la extensión y se reinicie KIROHA.

### Descargas anteriores no reconocidas

La primera fase solo reconocía `ComicInfo.xml` de carpetas y CBZ bajo URI `file://`, NO CBZ de SAF `content://`. La recuperación ahora lee el XML en CBZ SAF mediante `archiveReader(context)` ya presente en el proyecto, sin duplicar la descarga gigante, y rechaza XML de más de 512 KiB.

Los archivos antiguos cuyo nombre incluía el hash calculado desde la URL exacta del capítulo ahora pueden reconocerse por identidad incluso si no incluyen ComicInfo. Para directorios antiguos sin hash, solo se permite el reconocimiento cuando `ComicInfo.Web` confirma URL del capítulo.

**Nunca atribuir carpetas antiguas por coincidencia de título solamente**: pueden existir muchas galerías E-Hentai con idéntico nombre pero páginas diferentes. Se conservan los archivos sin mover, renombrar ni borrar nada.

Los archivos sin hash ni ComicInfo suficiente siguen requiriendo un mecanismo de asociación manual, explícita, con copia de seguridad.

## Pruebas indispensables para Manus

1. Mantener identificador efectivo `app.kiroha.mobile.dev` y exactamente el mismo certificado de firma para actualizar en lugar de reinstalar.
2. Compilar **versionCode 147**, versionName `0.1.3`, sin borrar datos.
3. Con la extensión E-Hentai instalada, poner dos capítulos de manga y un episodio de anime en las colas, pausar y cerrar interfaz. Abrir KIROHA otra vez; deben seguir presentes en sus respectivas pestañas.
4. Repetir al forzar finalización del proceso con Android/ADB (sin pulsar «Borrar datos»), abrir app, esperar carga de extensiones y verificar que vuelve la cola y puede continuar.
5. Desactivar temporalmente una extensión y comprobar que no se eliminan sus registros persistidos; instalarla de nuevo y verificar reaparición después de reabrir la app.
6. Buscar capítulos ya descargados en `Aniyomi/downloads/E-Hentai (EN)` y verificar reconocimiento desde biblioteca, tanto en carpetas de imágenes como en CBZ bajo SAF.
7. Dos galerías de igual título pero URL diferente no deben apropiarse de un capítulo ajeno.
8. En archivos que carecen tanto de hash de URL como de ComicInfo válido, mostrar que siguen sin autoasociar y que no se ha perdido el contenido.
9. Ejecutar tests y verificar CPU/calor: nada de escaneos globales repetidos del árbol con 714 carpetas.
10. Revisar errores de compilación sin eliminar funciones ni cambiar firma.

**Estado:** modificaciones de código subidas; compilación de Android y pruebas reales quedan pendientes. No declarar resuelta definitivamente hasta completar pruebas.
