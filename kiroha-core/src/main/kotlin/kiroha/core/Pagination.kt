package kiroha.core

data class PageDeduplication<T>(val items: List<T>, val hasNext: Boolean, val duplicatesRemoved: Int)

/**
 * Only the extension is authoritative about whether a next page exists.
 * Skipping repeated/empty pages is handled by SourcePagingSource, which has
 * an explicit bounded request budget and surfaces errors instead of silently
 * discarding later unique pages.
 */
class PageDeduplicator<T>(private val canonicalKey: (T) -> String) {
    private val seen = hashSetOf<String>()
    private val acceptedKeysByPage = sortedMapOf<Long, Set<String>>()

    /**
     * Keep the source's original order, removing only repeated URLs.
     *
     * Unlike a global "seen" set, this operation is safe when Paging retries
     * a previously requested page: the retried page must not be mistaken for
     * a page containing only duplicates of itself.
     *
     * One instance belongs to exactly one listing/query generation.
     */
    fun acceptPage(page: Long, items: Iterable<T>, hasNextPage: Boolean): PageDeduplication<T> {
        require(page >= 1L) { "Page numbers start at 1" }

        if (acceptedKeysByPage.isNotEmpty() && page <= acceptedKeysByPage.lastKey()) {
            val stale = acceptedKeysByPage.tailMap(page).keys.toList()
            stale.forEach { acceptedKeysByPage.remove(it) }
            seen.clear()
            acceptedKeysByPage.values.forEach { seen.addAll(it) }
        }

        var removed = 0
        val accepted = hashSetOf<String>()
        val unique = buildList {
            for (item in items) {
                val key = canonicalKey(item).trim()
                if (key.isNotEmpty() && seen.add(key)) {
                    accepted.add(key)
                    add(item)
                } else {
                    removed++
                }
            }
        }
        acceptedKeysByPage[page] = accepted
        return PageDeduplication(unique, hasNextPage, removed)
    }

    fun accept(items: Iterable<T>, hasNextPage: Boolean): PageDeduplication<T> {
        var removed = 0
        val unique = buildList {
            for (item in items) {
                val key = canonicalKey(item).trim()
                if (key.isNotEmpty() && seen.add(key)) {
                    add(item)
                } else {
                    removed++
                }
            }
        }
        return PageDeduplication(unique, hasNextPage, removed)
    }
}
