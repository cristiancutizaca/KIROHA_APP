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
