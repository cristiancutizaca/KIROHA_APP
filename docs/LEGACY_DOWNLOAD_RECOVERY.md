# KIROHA: compatibilidad segura con descargas anteriores (14/10/2026)

## Motivo
Antes las descargas se guardaban bajo `downloads/<nombre de fuente>/<titulo>/<capitulo>`. Las versiones nuevas separan fuentes mediante ID y obras mediante fuente+URL, porque títulos coincidentes podían compartir descargas.

## Comportamiento implementado
- **No se mueve, borra, renombra ni copia ningún archivo antiguo.** Las descargas nuevas siguen guardándose en las carpetas actuales con identificadores.
- Si un capítulo no está en la ruta nueva, se permite **lectura** de un capítulo ubicado en la ruta anterior ÚNICAMENTE cuando su `ComicInfo.xml` contiene en `Web` la URL exacta del capítulo devuelta por la extensión.
- Se verifica el capítulo individual, **nunca** se atribuye toda una carpeta de título a una obra.
- Las carpetas antiguas sin metadatos identificables permanecen intactas y no se marcan descargadas por coincidencia de título.
- Los CBZ antiguos bajo URI `file://` pueden inspeccionar el `ComicInfo.xml` con `ZipFile` sin recorrer las imágenes. Los CBZ bajo URI SAF `content://` requieren recuperación manual posterior si no pueden verificarse sin lectura masiva.
- La detección destinada a la biblioteca se ejecuta en segundo plano, **un capítulo a la vez**, con actualización de UI agrupada para no volver a calentar el teléfono recorriendo cientos de carpetas.
- El comando Ajustes > Avanzado > Reconstruir el índice de descargas vacía también la caché de detecciones antiguas para volver a comprobar capítulos conforme se consultan.
- `App.kt` ahora pasa la URL de la obra al registrador de descargas, para que no falle la búsqueda en el nuevo esquema.

## Alcance y límites
Esta etapa **no migra archivos físicamente ni recupera de forma automática todos los archivos de los directorios antiguos**. La recuperación sin metadatos suficientes debe exigir confirmación explícita de identidad antes de asociar una carpeta a una obra. En especial, E-Hentai puede tener dos galerías con igual título y diferente URL; NO aplicar equivalencia por título.

## Pruebas pendientes (NO declarar verificado en Android)
1. Instalar como actualización firmada con la misma clave. Hacer copia de seguridad y no desinstalar APK actual.
2. Abrir un capítulo antiguo con ComicInfo correcto y comprobar que se reconoce sin duplicar archivos ni crear carpetas nuevas.
3. Abrir dos galerías con idéntico título pero URL diferente; verificar que la descarga de A no aparece en B.
4. Repetir con capítulos sin ComicInfo, XML con otra URL, CBZ bajo SAF y archivos corruptos; nunca atribuirlos por título.
5. Reindexar y volver a abrir biblioteca. Validar uso de CPU, memoria, batería y temperatura.
6. Compilar y revisar `spotlessCheck`, `compileDebugKotlin` y pruebas de dispositivo real.

Si falta compatibilidad con archivos antiguos, añadir posteriormente una pantalla de **vinculación manual segura**, con copia de seguridad y confirmación de usuario, antes de permitir renombrar/mover.
