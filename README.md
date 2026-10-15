<div align="center">

# ✦ KIROHA

### Tu biblioteca. Tus historias. Tu reproductor.

**Lector de manga y reproductor de anime para Android.**  
Organiza, explora, descarga y disfruta tu contenido con una experiencia personalizable.

[**Funciones**](#-qué-puedes-hacer) · [**Instalación**](#-instalación) · [**Compilación**](#-compilación) · [**Estado**](#-estado-del-proyecto) · [**Créditos**](#-origen-y-créditos)

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Build](https://img.shields.io/badge/Estado-En%20desarrollo-FFA500?style=flat-square)
![Licencia](https://img.shields.io/badge/Licencia-Apache--2.0-64748b?style=flat-square)

</div>

---

## 🌸 ¿Qué es KIROHA?

**KIROHA** es una aplicación Android de código abierto que reúne lectura de manga y reproducción de anime en una misma biblioteca, con extensiones, descargas locales y controles avanzados.

El objetivo es conservar las funciones útiles del ecosistema de lectores y reproductores existentes mientras se desarrolla una experiencia propia: más rápida, organizada, adaptable y centrada en los archivos del usuario.

> [!IMPORTANT]
> **Proyecto en desarrollo.** Hay funciones terminadas, otras experimentales y algunas pendientes. Las modificaciones recientes deben compilarse y validarse antes de usarlas como versión estable. No desinstales una versión con datos importantes sin disponer de una copia de seguridad.

## ✨ Qué puedes hacer

| 📖 Manga | 📺 Anime | 📂 Biblioteca y descargas |
|:--|:--|:--|
| Lectura online y local | Reproducción con motor **mpv** | Fuentes mediante extensiones |
| Visores y orientación ajustables | Pistas de audio y subtítulos | Categorías y organización |
| Historial y progreso | Velocidad ajustable y preajustes | Cola de descargas |
| Descarga de capítulos | Relación de aspecto y calidad | Lectura/reproducción sin conexión |
| Búsqueda y metadatos | Gestos de brillo, volumen y avance | Reconstrucción de índice |
| Fuentes y galerías independientes | PiP cuando Android lo permite | Identidad de descargas por fuente y URL |

### 🎬 Reproductor de KIROHA

El reproductor está basado en **mpv** y ofrece una interfaz con controles rápidos de estilo reproductor multimedia:

- ⏪ **Retroceder 10 s / avanzar 10 s** sin salir del video.
- ⏯️ Pausa, reproducción y cambio entre episodios.
- ⚡ Velocidades rápidas **0,75× · 1× · 1,25× · 1,5× · 2×**, con ajuste detallado al mantener pulsado el control de velocidad.
- 🚀 Mantener pulsada la imagen para **2× temporal**, con regreso a la velocidad anterior al soltar. Mantener pulsado el extremo izquierdo conserva el acceso a capturas.
- 👆 Doble toque para buscar, desplazamiento horizontal y deslizamientos de brillo/volumen.
- 💬 Subtítulos, pistas de audio, previsualización de búsqueda cuando esté disponible, bloqueo de controles y Picture-in-Picture cuando sea compatible.

Estas mejoras de interfaz están **en código** y todavía requieren validación en APK y teléfonos reales.

### 📥 Descargas: compatibilidad y seguridad

KIROHA está desarrollando un sistema que distingue obras con el mismo título mediante la **fuente y la URL de cada obra**, para impedir que una descarga aparezca atribuida a una galería diferente.

Las carpetas históricas, por ejemplo `E-Hentai (EN)`, y las nuevas como `E-Hentai (EN)__s_<id>`, se buscan como **ubicaciones alternativas de lectura**. Esto **no significa que se mezclen sus contenidos**: la atribución de cada capítulo requiere una identidad verificable.

Los archivos antiguos **no se eliminan ni se mueven automáticamente**. Algunos descargados sin metadatos suficientes aún podrían necesitar asociación manual. Consulta [compatibilidad de carpetas](docs/DUAL_SOURCE_FOLDER_COMPAT_148.md).

## 🧩 KIROHA Core y sincronización

El módulo `kiroha-core` reúne la identidad de dispositivos y descargas, el seguimiento de progreso, índices de almacenamiento y piezas de un protocolo de sincronización. También existe un servidor de referencia en `kiroha-sync-server/`.

> La **sincronización automática y completa de bibliotecas entre varios celulares** todavía está en desarrollo. No la confundas con una función terminada de la APK.

Documentación: [estado](PROJECT_STATUS.md) · [plan](ROADMAP.md) · [Core](docs/KIROHA_CORE.md) · [sincronización](docs/SYNC_ANDROID.md).

## 📲 Instalación

**Requisitos:** Android **8.0 o superior** y espacio suficiente para las descargas que elijas.

No hay que confundir **un commit de GitHub** con una APK comprobada. Las nuevas compilaciones se prueban antes de ofrecerlas como instalación general.

**Si ya tienes KIROHA con tus bibliotecas:** instala solamente una APK firmada con la misma clave y con el mismo identificador de paquete. Las compilaciones `debug` utilizan `app.kiroha.mobile.dev`, distinto al paquete de lanzamiento. **No borres los datos de la aplicación ni las carpetas de descargas.**

- [Repositorio y código fuente](https://github.com/cristiancutizaca/KIROHA_APP)
- [Compilaciones automáticas y resultados](https://github.com/cristiancutizaca/KIROHA_APP/actions)
- [Reportar un error](https://github.com/cristiancutizaca/KIROHA_APP/issues)

## 🛠️ Compilación

El proyecto utiliza **Kotlin**, **Jetpack Compose**, **Gradle**, módulos Android y un motor de reproducción nativo.

**Entorno de referencia:**

- **JDK 21**.
- Android SDK (plataforma 37 y Build Tools 37, según la configuración actual).
- Android NDK y CMake compatibles con la versión indicada en el repositorio.
- Gradle Wrapper incluido: **no es necesario instalar Gradle globalmente**.

```bash
git clone https://github.com/cristiancutizaca/KIROHA_APP.git
cd KIROHA_APP

# Configura JAVA_HOME y ANDROID_HOME para tu equipo.
./gradlew :kiroha-core:test :app:assembleDebug
```

La APK de desarrollo se genera, si la compilación termina correctamente, en `app/build/outputs/apk/debug/`. Los pasos y las dependencias nativas pueden variar según el entorno. [Incidencias conocidas](KNOWN_ISSUES.md).

## 🧭 Estado del proyecto

| Componente | Estado |
|:--|:--|
| Interfaz de lectura y biblioteca | 🟢 Base funcional |
| Reproductor mpv y controles | 🟢 Base funcional |
| Botones ±10 s, velocidad rápida, pulsación 2× | 🟡 Implementado en código; pendiente de prueba en APK |
| Persistencia de cola de descargas | 🟡 Corregida en código; validar en más dispositivos |
| Descargas antiguas de Aniyomi/Animetail | 🟡 Recuperación parcial; no migración destructiva |
| Bibliotecas con títulos duplicados | 🟡 Mejoras de identidad, requieren pruebas |
| Sincronización automática entre dispositivos | 🔧 En desarrollo |
| Compilación y validación de la rama actual | 🔴 Pendiente |

No presentamos funciones experimentales como si ya estuvieran terminadas. Revisa el [registro de problemas](KNOWN_ISSUES.md) antes de probar una actualización.

## 🤝 Colaborar

Se aceptan reportes reproducibles, documentación, traducciones y propuestas de código.

Para informar un fallo, indica la versión, el dispositivo, Android, los pasos para reproducirlo y capturas o registros **sin datos privados**. Abre una [incidencia](https://github.com/cristiancutizaca/KIROHA_APP/issues) y revisa [CONTRIBUTING.md](CONTRIBUTING.md) y [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## 🙏 Origen y créditos

**KIROHA es un proyecto derivado, no una aplicación creada desde cero.** Se basa en [**Animetail (repositorio original)**](https://github.com/Animetailapp/Animetail), y hereda código y aportes del ecosistema [Aniyomi](https://github.com/aniyomiorg/aniyomi), [Mihon](https://github.com/mihonapp/mihon) y otros proyectos y colaboradores, incluido el reproductor basado en mpv.

Los derechos de autor, los avisos de licencia y los créditos de los proyectos originales se conservan donde corresponde. **KIROHA no está afiliada oficialmente a los equipos anteriores.** Este repositorio no solicita donaciones en su nombre ni dirige pagos a cuentas de terceros.

Consulta [LICENSE](LICENSE), los encabezados de los archivos originales y el apartado de **Licencias** dentro de la aplicación.

## ⚖️ Aviso legal

Esta aplicación **no aloja contenido audiovisual o editorial**. Las fuentes y extensiones son responsabilidad de sus respectivos autores y servicios. Utiliza únicamente contenido al que tengas derecho de acceso y respeta las leyes aplicables.

Distribuido bajo la **Apache License 2.0**, sujeto a las licencias de sus dependencias y a los avisos aplicables de los componentes derivados.

---

<div align="center"><strong>KIROHA</strong> · Hecho para leer, ver y organizar sin perder el control de tu biblioteca.</div>
