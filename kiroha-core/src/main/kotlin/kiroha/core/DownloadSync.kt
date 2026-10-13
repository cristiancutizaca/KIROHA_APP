package kiroha.core

/** Converts a remote DOWNLOAD event into a receiver-side metadata-only copy. */
object DownloadSyncReducer {
    fun applyRemote(
        records: Iterable<DownloadRecord>,
        remoteDeviceId: String,
        remoteStorageLabel: String,
        itemId: String,
        remoteState: DownloadAvailability,
        downloadedEver: Boolean,
        size: Long,
        lastVerifiedAt: Long?,
        sourceNamespace: String?,
        canonicalId: String?,
    ): List<DownloadRecord> {
        val state = when (remoteState) {
            DownloadAvailability.AVAILABLE_LOCAL -> DownloadAvailability.AVAILABLE_REMOTE
            DownloadAvailability.STORAGE_UNAVAILABLE,
            DownloadAvailability.MISSING,
            -> DownloadAvailability.LAST_KNOWN_REMOTE
            else -> remoteState
        }
        val incoming = DownloadRecord(
            itemId = itemId,
            deviceId = remoteDeviceId,
            storageId = remoteStorageLabel,
            state = state,
            downloadedEver = downloadedEver,
            localPath = null,
            size = size,
            lastVerifiedAt = lastVerifiedAt,
            sourceNamespace = sourceNamespace,
            canonicalId = canonicalId,
        )
        return records.filterNot { it.itemId == itemId && it.deviceId == remoteDeviceId && it.storageId == remoteStorageLabel } + incoming
    }
}
