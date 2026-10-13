package kiroha.core

import java.security.SecureRandom
import java.util.Base64

/** Platform values are deliberately not Android-specific so Desktop can reuse the protocol. */
enum class Platform { ANDROID, WINDOWS, LINUX }

data class DeviceCapabilities(
    val canRead: Boolean = true,
    val canDownload: Boolean = true,
    val canHostStorage: Boolean = true,
    val canSync: Boolean = true,
)

data class DeviceRecord(
    val deviceId: String,
    val deviceName: String,
    val platform: Platform,
    val createdAt: Long,
    val lastSeen: Long,
    val appVersion: String,
    val capabilities: DeviceCapabilities = DeviceCapabilities(),
)

/** Minimal persistence boundary. Android can back this with DataStore/SQLDelight without changing the core. */
interface KeyValueStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String) { values[key] = value }
}

class DeviceRegistry(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = ::secureDeviceId,
) {
    fun getOrCreate(deviceName: String, appVersion: String, capabilities: DeviceCapabilities = DeviceCapabilities()): DeviceRecord {
        val existing = load()
        if (existing != null) {
            val refreshed = existing.copy(deviceName = deviceName, lastSeen = clock(), appVersion = appVersion, capabilities = capabilities)
            save(refreshed)
            return refreshed
        }
        val now = clock()
        return DeviceRecord(idGenerator(), deviceName, Platform.ANDROID, now, now, appVersion, capabilities).also(::save)
    }

    fun markSeen(): DeviceRecord? = load()?.copy(lastSeen = clock())?.also(::save)

    fun load(): DeviceRecord? = store.read(KEY)?.split(DELIMITER)?.takeIf { it.size == 10 }?.let { fields ->
        DeviceRecord(
            deviceId = fields[0], deviceName = fields[1], platform = Platform.valueOf(fields[2]),
            createdAt = fields[3].toLong(), lastSeen = fields[4].toLong(), appVersion = fields[5],
            capabilities = DeviceCapabilities(fields[6].toBoolean(), fields[7].toBoolean(), fields[8].toBoolean(), fields[9].toBoolean()),
        )
    }

    private fun save(record: DeviceRecord) {
        store.write(KEY, listOf(record.deviceId, record.deviceName, record.platform, record.createdAt, record.lastSeen, record.appVersion,
            record.capabilities.canRead, record.capabilities.canDownload, record.capabilities.canHostStorage, record.capabilities.canSync).joinToString(DELIMITER))
    }

    private companion object { const val KEY = "kiroha.device.registry.v1"; const val DELIMITER = "|" }
}

enum class DownloadAvailability { AVAILABLE_LOCAL, AVAILABLE_REMOTE, LAST_KNOWN_REMOTE, STORAGE_UNAVAILABLE, MISSING, DELETED, ERROR }

data class DownloadRecord(
    val itemId: String,
    val deviceId: String,
    val storageId: String,
    val state: DownloadAvailability,
    val downloadedEver: Boolean,
    val localPath: String? = null,
    val size: Long = 0,
    val checksum: String? = null,
    val downloadedAt: Long? = null,
    val lastVerifiedAt: Long? = null,
    val deletedAt: Long? = null,
    val chapterId: String? = null,
    val sourceNamespace: String? = null,
    val canonicalId: String? = null,
)

object DownloadStateResolver {
    fun markDeleted(record: DownloadRecord, now: Long): DownloadRecord = record.copy(
        state = DownloadAvailability.DELETED,
        downloadedEver = true,
        deletedAt = now,
        lastVerifiedAt = now,
    )

    fun markMissing(record: DownloadRecord, now: Long): DownloadRecord = record.copy(
        state = DownloadAvailability.MISSING,
        downloadedEver = record.downloadedEver || record.state != DownloadAvailability.MISSING,
        lastVerifiedAt = now,
    )

    fun isDownloadedGlobally(records: Iterable<DownloadRecord>, itemId: String): Boolean = records.any {
        it.itemId == itemId && it.state in setOf(DownloadAvailability.AVAILABLE_LOCAL, DownloadAvailability.AVAILABLE_REMOTE, DownloadAvailability.LAST_KNOWN_REMOTE, DownloadAvailability.STORAGE_UNAVAILABLE)
    }

    fun isAvailableHere(records: Iterable<DownloadRecord>, itemId: String, currentDeviceId: String): Boolean = records.any {
        it.itemId == itemId && it.deviceId == currentDeviceId && it.state == DownloadAvailability.AVAILABLE_LOCAL
    }

    fun downloadedEver(records: Iterable<DownloadRecord>, itemId: String): Boolean = records.any { it.itemId == itemId && it.downloadedEver }
}

enum class ProgressKind { MANGA_PAGE, VIDEO_POSITION }
data class ProgressEvent(val itemId: String, val chapterId: String, val position: Long, val kind: ProgressKind, val updatedAt: Long, val deviceId: String)

object KirohaIdentity {
    fun manga(sourceNamespace: String, chapterCanonicalId: String): String =
        "manga:$sourceNamespace:$chapterCanonicalId"

    fun anime(sourceNamespace: String, episodeCanonicalId: String): String =
        "anime:$sourceNamespace:$episodeCanonicalId"

    fun storage(uriOrPath: String, saf: Boolean): String =
        (if (saf) "saf_" else "file_") + uriOrPath.sha256().take(16)

    private fun String.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Resolves a remote progress record against the receiver's local numeric ID. */
data class CanonicalProgressTarget(val canonicalId: String, val localId: Long, val sourceNamespace: String)

object CanonicalProgressResolver {
    fun <T> resolve(
        sourceNamespace: String,
        canonicalId: String,
        localItems: Iterable<T>,
        canonical: (T) -> String,
        localId: (T) -> Long,
    ): CanonicalProgressTarget? = localItems.firstOrNull { canonical(it) == canonicalId }
        ?.let { CanonicalProgressTarget(canonicalId, localId(it), sourceNamespace) }
}

/** Conflict policy: manga prefers the furthest page; video prefers the newest timestamp. */
object ProgressConflictResolver {
    fun merge(a: ProgressEvent, b: ProgressEvent): ProgressEvent {
        require(a.itemId == b.itemId && a.chapterId == b.chapterId && a.kind == b.kind)
        return when (a.kind) {
            ProgressKind.MANGA_PAGE -> if (a.position >= b.position) a else b
            ProgressKind.VIDEO_POSITION -> if (a.updatedAt >= b.updatedAt) a else b
        }
    }
}

private fun secureDeviceId(): String {
    val bytes = ByteArray(18)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
