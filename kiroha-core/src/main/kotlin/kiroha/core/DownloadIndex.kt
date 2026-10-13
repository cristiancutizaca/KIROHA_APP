package kiroha.core

/** Fast projection over persisted DownloadRecords; lookups never scan the filesystem. */
class DownloadIndex(records: Iterable<DownloadRecord> = emptyList()) {
    private val byItem = mutableMapOf<String, MutableList<DownloadRecord>>()
    private val byChapter = mutableMapOf<String, MutableSet<String>>()
    private val byDevice = mutableMapOf<String, MutableSet<String>>()
    private val byStorage = mutableMapOf<String, MutableSet<String>>()
    private val bySourceCanonical = mutableMapOf<String, MutableSet<String>>()

    init { reindex(records) }

    fun reindex(records: Iterable<DownloadRecord>) {
        byItem.clear(); byChapter.clear(); byDevice.clear(); byStorage.clear(); bySourceCanonical.clear()
        records.forEach(::update)
    }

    fun update(record: DownloadRecord) {
        val records = byItem.getOrPut(record.itemId) { mutableListOf() }
        records.removeAll { it.deviceId == record.deviceId && it.storageId == record.storageId }
        records += record
        byDevice.getOrPut(record.deviceId) { mutableSetOf() }.add(record.itemId)
        byStorage.getOrPut(record.storageId) { mutableSetOf() }.add(record.itemId)
        record.chapterId?.let { byChapter.getOrPut(it) { mutableSetOf() }.add(record.itemId) }
        val key = sourceKey(record.sourceNamespace, record.canonicalId)
        if (key != null) bySourceCanonical.getOrPut(key) { mutableSetOf() }.add(record.itemId)
    }

    fun remove(itemId: String, deviceId: String, storageId: String) {
        val remaining = recordsFor(itemId).filterNot { it.deviceId == deviceId && it.storageId == storageId }
        if (remaining.isEmpty()) byItem.remove(itemId) else byItem[itemId] = remaining.toMutableList()
        reindex(byItem.values.flatten())
    }

    fun recordsFor(itemId: String): List<DownloadRecord> = byItem[itemId].orEmpty().toList()
    fun recordsForChapter(chapterId: String): List<DownloadRecord> = byChapter[chapterId].orEmpty().flatMap(::recordsFor)
    fun recordsForDevice(deviceId: String): List<DownloadRecord> = byDevice[deviceId].orEmpty().flatMap(::recordsFor)
    fun recordsForStorage(storageId: String): List<DownloadRecord> = byStorage[storageId].orEmpty().flatMap(::recordsFor)
    fun recordsForSource(sourceNamespace: String, canonicalId: String): List<DownloadRecord> =
        bySourceCanonical[sourceKey(sourceNamespace, canonicalId)].orEmpty().flatMap(::recordsFor)

    fun isDownloaded(itemId: String): Boolean = recordsFor(itemId).any { it.isKnownCopy() }
    fun isAvailableHere(itemId: String, deviceId: String): Boolean = recordsFor(itemId).any {
        it.deviceId == deviceId && it.state == DownloadAvailability.AVAILABLE_LOCAL
    }
    fun isAvailableRemotely(itemId: String, deviceId: String): Boolean = recordsFor(itemId).any {
        it.deviceId != deviceId && it.isKnownCopy()
    }
    fun isOffline(itemId: String): Boolean = recordsFor(itemId).any {
        it.state == DownloadAvailability.STORAGE_UNAVAILABLE
    }
    fun isHistory(itemId: String): Boolean = recordsFor(itemId).any {
        it.downloadedEver && !it.isKnownCopy()
    }
    fun sizeFor(itemId: String): Long = recordsFor(itemId).filter { it.isKnownCopy() }.sumOf { it.size.coerceAtLeast(0) }
    fun lastVerifiedFor(itemId: String): Long? = recordsFor(itemId).maxOfOrNull { it.lastVerifiedAt ?: 0L }?.takeIf { it > 0L }

    fun resolve(itemId: String, currentDeviceId: String): DownloadRecord? = recordsFor(itemId)
        .sortedWith(
            compareByDescending<DownloadRecord> { it.deviceId == currentDeviceId && it.state == DownloadAvailability.AVAILABLE_LOCAL }
                .thenByDescending { it.state == DownloadAvailability.AVAILABLE_REMOTE }
                .thenByDescending { it.state == DownloadAvailability.LAST_KNOWN_REMOTE }
                .thenByDescending { it.state == DownloadAvailability.STORAGE_UNAVAILABLE }
                .thenByDescending { it.lastVerifiedAt ?: 0L },
        )
        .firstOrNull { it.state != DownloadAvailability.MISSING && it.state != DownloadAvailability.DELETED && it.state != DownloadAvailability.ERROR }

    private fun DownloadRecord.isKnownCopy() = state in setOf(
        DownloadAvailability.AVAILABLE_LOCAL,
        DownloadAvailability.AVAILABLE_REMOTE,
        DownloadAvailability.LAST_KNOWN_REMOTE,
        DownloadAvailability.STORAGE_UNAVAILABLE,
    )

    private fun sourceKey(source: String?, canonical: String?): String? =
        if (source.isNullOrBlank() || canonical.isNullOrBlank()) null else "$source\u0000$canonical"
}
