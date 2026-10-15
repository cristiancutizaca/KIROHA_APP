# KIROHA 0.1.4 / 148 — carpetas de descarga de la misma extensión

## Solicitud
La carpeta anterior `Aniyomi/downloads/E-Hentai (EN)/` contiene una biblioteca grande. Las APK recientes también escriben en `Aniyomi/downloads/E-Hentai (EN)__s_57122881048805941/`. El usuario quiere que ambas ubicaciones sean visibles desde KIROHA sin perder las descargas.

## Cambios
- Lectura simultánea de carpetas cuyo nombre de fuente coincida, ignorando **exclusivamente** un sufijo de identificación `__s_<dígitos>`. No combinar etiquetas de extensiones diferentes.
- Se contemplan las dos opciones históricas de saneamiento de caracteres no ASCII en los nombres de fuente y manga.
- El escaneo de carpetas de fuente es **una vez por ubicación**, con índice temporal y caducidad al reconstruir el índice de descargas.
- La carpeta nueva de cada descarga **sigue separada por ID y URL de manga**. No mezclamos ni movemos archivos físicos.
- **Cada capítulo antiguo** se reconoce si el nombre incluye el hash de la URL exacta del capítulo o su `ComicInfo.xml` (también CBZ SAF) contiene la URL del capítulo o galería. Dos galerías con igual título y URL distinta NO deben reutilizar automáticamente el mismo archivo.
- El contador de capítulos en biblioteca tiene en cuenta los archivos antiguos que ya hayan sido identificados correctamente.

## Limitaciones
- El simple parecido del título de una galería **no basta** para demostrar que es el mismo contenido. Una carpeta vieja sin hash o sin metadata confiable permanece sin asociar hasta que exista una operación de vinculación manual explícita por serie.
- Los resultados de reconocimiento antiguo se calculan en segundo plano al consultar capítulos. Una biblioteca de cientos de series no se recorre íntegramente en cada inicio.
- No se ha verificado aún un APK instalado. Antes de probar, compilar `app.kiroha.mobile.dev` con la firma anterior y `versionCode = 148`.

## Pruebas
1. Biblioteca de E-Hentai de más de 700 carpetas: no calentar el teléfono escaneándola en cada pantalla.
2. Capítulo con hash/ComicInfo válido guardado solo en carpeta antigua: debe mostrarse descargado y abrirse.
3. El contador de la obra debe reflejar los capítulos antiguos verificados.
4. Abrir la misma obra con contenido en carpeta antigua y en nueva: ambas deben encontrarse sin copiar archivos.
5. Dos galerías con el mismo título pero URL diferente: no tomar la descarga de la otra.
6. Verificar que la cola continúa después de cerrar y abrir KIROHA, sin cambiar su implementación corregida en la versión 147.
7. Comprobar casos en que falta metadata, archivos CBZ SAF y cambios de ajustes de caracteres.
8. Mantener copia de seguridad. No desinstalar, borrar datos, mover ni renombrar archivos.
