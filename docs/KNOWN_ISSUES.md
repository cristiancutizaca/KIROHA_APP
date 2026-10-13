# Known Issues

- No hay `adb` ni emulador instalado en el sandbox; la APK compila, pero el flujo UI no pudo ejecutarse en dispositivo.
- `app/google-services.json` todavía declara clientes Firebase para `com.dark.animetailv2` y su variante debug. Registrar `app.kiroha.mobile` en Firebase requiere una acción externa y credenciales del proyecto; no se sustituyó con valores inventados.
- El journal local usa `SharedPreferences` como bootstrap. Para biblioteca masiva y Sync incremental debe migrarse a SQLDelight con índices y transacciones.
- El transporte y autenticación Android ↔ Android aún son pendientes; el heartbeat local no implica presencia remota.
- Persisten warnings heredados de deprecación de Kotlin/Java en módulos Animetail/Anynomi/Mihon; no bloquearon el build.
