package kiroha.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.util.Base64

/** SharedPreferences adapter used for the small bootstrap registry and event journal. */
class AndroidKeyValueStore(
    context: Context,
    private val preferencesName: String = "kiroha_core_v1",
) : KeyValueStore {
    private val preferences: SharedPreferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(key: String, value: String) {
        check(preferences.edit().putString(key, value).commit()) { "Unable to persist Kiroha key: $key" }
    }
}

object AndroidDeviceInfo {
    fun name(context: Context): String {
        val configuredName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        return configuredName?.takeIf { it.isNotBlank() } ?: listOf(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifBlank { "Android device" }
    }
}

enum class SyncEntity { DEVICE, LIBRARY, PROGRESS, HISTORY, FAVORITE, CATEGORY, DOWNLOAD }
enum class SyncOperation { UPSERT, DELETE, HEARTBEAT }

data class SyncEvent(
    val eventId: String,
    val entity: SyncEntity,
    val entityId: String,
    val operation: SyncOperation,
    val revision: Long,
    val timestamp: Long,
    val deviceId: String,
    val payload: String,
)

/** Local-first append-only journal. A network transport can drain and acknowledge it later. */
class LocalSyncJournal(
    private val store: KeyValueStore,
    private val idGenerator: () -> String = ::secureEventId,
) {
    fun append(event: SyncEvent): SyncEvent {
        val normalized = if (event.eventId.isBlank()) event.copy(eventId = idGenerator()) else event
        val encoded = encode(normalized)
        val current = store.read(KEY).orEmpty()
        store.write(KEY, if (current.isBlank()) encoded else "$current\n$encoded")
        return normalized
    }

    fun pending(): List<SyncEvent> = store.read(KEY).orEmpty()
        .lineSequence()
        .filter(String::isNotBlank)
        .mapNotNull(::decode)
        .toList()

    fun acknowledge(eventIds: Set<String>) {
        val remaining = pending().filterNot { it.eventId in eventIds }
        store.write(KEY, remaining.joinToString("\n", transform = ::encode))
    }

    private fun encode(event: SyncEvent): String = listOf(
        event.eventId, event.entity.name, event.entityId, event.operation.name,
        event.revision.toString(), event.timestamp.toString(), event.deviceId, event.payload,
    ).joinToString("\t", transform = ::encodePart)

    private fun decode(value: String): SyncEvent? = runCatching {
        val fields = value.split('\t').map(::decodePart)
        require(fields.size == 8)
        SyncEvent(
            eventId = fields[0], entity = SyncEntity.valueOf(fields[1]), entityId = fields[2],
            operation = SyncOperation.valueOf(fields[3]), revision = fields[4].toLong(),
            timestamp = fields[5].toLong(), deviceId = fields[6], payload = fields[7],
        )
    }.getOrNull()

    private companion object {
        const val KEY = "kiroha.sync.journal.v1"
        fun encodePart(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        fun decodePart(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    }
}

private fun secureEventId(): String = java.util.UUID.randomUUID().toString()
