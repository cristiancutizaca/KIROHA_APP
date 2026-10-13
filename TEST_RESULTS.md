# Kiroha v0.1 — Test Results (fixed final)

| Validación | Resultado |
|---|---|
| `./gradlew clean :kiroha-core:test :data:compileDebugKotlin :app:assembleDebug :app:packageDebugUniversalApk --no-daemon` | **SUCCESS** |
| Kiroha Core tests | **PASS** |
| Sync server E2E | **PASS** |
| Kiroha SQLDelight repository compile with async driver | **PASS** |
| ARM64 ABI | **PASS**, contiene `lib/arm64-v8a` |
| Universal ABI | **PASS**, contiene `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Official logo resources | **PASS**, adaptive/legacy/monochrome/splash empaquetados |
| Manga/anime pagination implementation | **COMPILED**, tests físicos de scroll pendientes |

El crash reportado por hardware se originaba en lecturas síncronas contra un driver SQLDelight asíncrono. El repositorio Kiroha fue migrado a `awaitAsList()` y `awaitAsOneOrNull()` y la ruta de startup/reindexado compila contra esa API. No quedan llamadas síncronas en la ruta Kiroha auditada.

La deduplicación de paginación usa `source + URL canónica` implícita por cada `PagingSource`, conserva obras con el mismo título pero URL distinta y corta tras dos páginas consecutivas sin elementos nuevos. La búsqueda global también deduplica antes de persistir.

No hay dispositivo Android, ADB, emulador ni microSD física en este entorno. Por eso el arranque real, scroll profundo, SAF/microSD, LAN Sync, reproducción y restauración de Player permanecen `HARDWARE TEST PENDING`, no se marcan como pruebas físicas exitosas.

## APK hashes

```text
863919bacc1f193b8b3fc0c583fd298f58894fa26bceeb6fec1aa51313f638d3  Kiroha_v0.1_arm64-v8a_debug.apk
26037c209058a4915a0eb0988d30e4251785e794b564de2b355f94dfb6c79088  Kiroha_v0.1_universal_debug.apk
```
