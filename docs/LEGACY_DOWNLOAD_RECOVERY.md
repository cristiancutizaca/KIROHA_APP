# KIROHA: compatibilidad segura con descargas anteriores (20/10/2026)

## Motivo
Antes las descargas se guardaban bajo `downloads/<nombre de fuente>/<titulo>/<capitulo>`. Las versiones nuevas separan fuentes mediante ID y obras mediante fuente+URL, porque títulos coincidentes podían compartir descargas.

## Comportamiento implementado
- **No se mueve, borra, renombra ni copia ningún archivo antiguo.** Las descargas nuevas siguen guardándose en las carpetas actuales con identificadores.
- Si un capítulo no está en la ruta nueva, se permite **lectura** de un capítulo ubicado en la ruta anterior ÚNICAMENTE cuando su `ComicInfo.xml` contiene en `Web` la URL exacta del capítulo devuelta por la extensión.
- Se verifica el capítulo individual, **nunca** se atribuye toda una carpeta de título a una obra.
- Las carpetas antiguas sin metadatos identificables permanecen intactas y no se marcan descargadas por coincidencia de título.
- Los CBZ antiguos bajo URI `file://` pueden inspeccionar el `ComicInfo.xml` con `ZipFile` sin recorrer las imágenes. Los CBZ bajo URI SAF `content://` requieren recuperación manual posterior si no pueden verificarse sin lectura masiva.
- **Nuevo 20/10:** se reconocen las carpetas antiguas `downloads/E-Hentai (EN)/<titulo>/<capitulo>` aunque el capítulo contenga únicamente imágenes y no tenga `ComicInfo.xml`, siempre que el título pertenezca a **una única obra favorita** de esa fuente en la biblioteca y haya una sola carpeta antigua con ese título.
- En obras con varios capítulos, el nombre del capítulo antiguo debe coincidir con alguna variante histórica de Kiroha/Aniyomi. En galerías con un único capítulo guardado y un solo capítulo registrado en la biblioteca, se permite el único subdirectorio con imágenes aunque cambie el nombre del capítulo.
- La recuperación almacena solo una asociación de lectura en memoria para el lector y los indicadores de descargas; mantiene las descargas antiguas donde estaban, sin tocar el nuevo esquema `__s_`/`__k_`.
- La detección de la biblioteca se ejecuta en segundo plano, **un capítulo a la vez**. Se usa una cola limitada para no ignorar permanentemente los elementos que exceden los primeros 24 y se espacian los reintentos para reducir la presión sobre la microSD.
- Las carpetas con títulos duplicados en la biblioteca, múltiples galerías antiguas equivalentes, archivos sin imágenes comprobables y capítulos ambiguos siguen excluidas del reconocimiento automático.
- El comando Ajustes > Avanzado > Reconstruir el índice de descargas vacía también la caché de detecciones antiguas para volver a comprobar capítulos conforme se consultan.
- `App.kt` ahora pasa la URL de la obra al registrador de descargas, para que no falle la búsqueda en el nuevo esquema.

## Alcance y límites
Esta etapa **no migra archivos físicamente**. La recuperación por título de imágenes antiguas solo es heurística y se restringe a un título único dentro de las obras favoritas registradas para esa fuente; no demuestra la identidad remota de una galería ausente de la biblioteca. La recuperación completamente segura de títulos duplicados requiere metadatos verificables o vinculación manual explícita. En especial, E-Hentai puede tener dos galerías con igual título y diferente URL; NO aplicar equivalencia global por título.

## Pruebas pendientes (NO declarar verificado en Android)
1. Instalar como actualización firmada con la misma clave. Hacer copia de seguridad y no desinstalar APK actual.
2. Abrir un capítulo antiguo con ComicInfo correcto y comprobar que se reconoce sin duplicar archivos ni crear carpetas nuevas.
3. Probar E-Hentai con una carpeta antigua de título único y un solo subdirectorio lleno de imágenes (sin ComicInfo), comprobar indicador de descarga y lectura offline.
4. Probar una obra antigua de varios capítulos cuyos nombres individuales coinciden, y otra cuyos nombres ya cambiaron: recuperar solamente los identificables.
5. Probar títulos favoritos duplicados (misma fuente) y álbumes con múltiples subdirectorios no identificables; no deben reclamarse de forma automática.
6. Abrir dos galerías con idéntico título pero URL diferente; verificar que la descarga de A no aparece en B.
7. Repetir con XML con otra URL, CBZ bajo SAF y archivos corruptos; nunca reclamar títulos ambiguos.
8. Reconstruir el índice y volver a abrir biblioteca. Validar uso de CPU, memoria, batería y temperatura.
9. Compilar y revisar `spotlessCheck`, `compileDebugKotlin` y pruebas de dispositivo real.

Si falta compatibilidad con archivos antiguos, añadir posteriormente una pantalla de **vinculación manual segura**, con copia de seguridad y confirmación de usuario, antes de permitir renombrar/mover.
