package kiroha.core

/** User-facing global download filters. This operates only on the DownloadIndex projection. */
enum class DownloadFilter {
    ALL,
    DOWNLOADED,
    NOT_DOWNLOADED,
    AVAILABLE_HERE,
    AVAILABLE_REMOTE,
    OFFLINE,
    DOWNLOADED_EVER,
}

data class DownloadFilterQuery(
    val filters: Set<DownloadFilter> = setOf(DownloadFilter.ALL),
    val read: Boolean? = null,
)

object DownloadFilterEngine {
    fun matches(
        index: DownloadIndex,
        itemId: String,
        currentDeviceId: String,
        query: DownloadFilterQuery,
        isRead: Boolean? = null,
    ): Boolean {
        val records = index.recordsFor(itemId)
        val filters = query.filters
        val matchesDownloads = filters.isEmpty() || DownloadFilter.ALL in filters || filters.all { filter ->
            when (filter) {
                DownloadFilter.ALL -> true
                DownloadFilter.DOWNLOADED -> index.isDownloaded(itemId)
                DownloadFilter.NOT_DOWNLOADED -> !index.isDownloaded(itemId)
                DownloadFilter.AVAILABLE_HERE -> index.isAvailableHere(itemId, currentDeviceId)
                DownloadFilter.AVAILABLE_REMOTE -> index.isAvailableRemotely(itemId, currentDeviceId)
                DownloadFilter.OFFLINE -> index.isOffline(itemId)
                DownloadFilter.DOWNLOADED_EVER -> index.isHistory(itemId) || records.any { it.downloadedEver }
            }
        }
        val matchesRead = query.read == null || isRead == null || query.read == isRead
        return matchesDownloads && matchesRead
    }
}
