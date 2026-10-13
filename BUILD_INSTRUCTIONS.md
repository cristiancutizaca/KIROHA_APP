# Kiroha — compilar, verificar y actualizar

Fuente canónica: rama `main` de `cristiancutizaca/KIROHA_APP`. Esta guía NO certifica que el HEAD actual compile: las ejecuciones de GitHub Actions del 13/10/2026 fallaron antes de ejecutar tareas.

## Requisitos

- JDK 21.
- Android SDK/NDK correspondientes a Gradle (Android Studio puede descargar componentes).
- Java/Gradle wrapper del proyecto, conexión para dependencias.
- En Linux, macOS o CI: `chmod +x gradlew`.

## Comprobaciones en Linux / macOS

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew :kiroha-core:test :data:compileDebugKotlin :app:compileDebugKotlin --stacktrace --no-daemon
./gradlew testDebugUnitTest verifySqlDelightMigration --stacktrace --no-daemon
./gradlew :app:assembleDebug --stacktrace --no-daemon
```

## Comprobaciones en Windows

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck
.\gradlew.bat :kiroha-core:test :data:compileDebugKotlin :app:compileDebugKotlin --stacktrace --no-daemon
.\gradlew.bat testDebugUnitTest verifySqlDelightMigration --stacktrace --no-daemon
.\gradlew.bat :app:assembleDebug --stacktrace --no-daemon
```

Las APK deberían aparecer en `app/build/outputs/apk/debug/`. El módulo configura ABI arm64-v8a, armeabi-v7a, x86, x86_64 y Universal, pero **comprueba las variantes realmente generadas**.

## Actualización sin borrar datos

- `applicationId`: `app.kiroha.mobile`; DEBUG: `app.kiroha.mobile.dev`. Son aplicaciones distintas.
- `versionCode` actual del código: `144`.
- Android exige el **mismo package y el mismo certificado de firma** para instalar como actualización. Cada máquina/runner puede crear una clave de depuración distinta; no existe solución automática al problema de firma.
- Si la firma coincide: `adb install -r ruta/a/Kiroha-debug.apk` (después de comprobar package). Si no coincide, NO desinstales la aplicación anterior antes de exportar respaldos de biblioteca y datos.
- Configura una clave de firma estable en tu entorno seguro antes de generar APK destinadas a actualizarse repetidamente. No publiques claves privadas en el repositorio.

## CI y APK

Workflow: `.github/workflows/kiroha_debug_apk.yml`. Si GitHub Actions funciona, intenta compilar y publicar artefactos arm64/universal con SHA256. En el estado actual los jobs fallan antes de sus pasos; esto no prueba éxito ni error de Kotlin.

## Diagnóstico del cierre al salir desde Browse

En la variante DEBUG se guardan solamente nombre de pantalla, tiempo de segundo plano y memoria usada en las preferencias privadas `kiroha_debug_lifecycle`. Para un fallo: capturar logcat con Android/ADB y revisar CrashActivity. No compartir tokens de extensiones, credenciales, URLs privadas o pairing secrets.

## Servidor Sync E2E / historial

```bash
python3 kiroha-sync-server/test_e2e.py
git bundle create Kiroha-history.bundle --all
git bundle verify Kiroha-history.bundle
```

Los comandos son instrucciones para ejecutar; no se afirma que se hayan ejecutado o aprobado en esta revisión.
