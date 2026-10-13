package kiroha.core

data class PageDeduplication<T>(val items: List<T>, val hasNext: Boolean, val duplicatesRemoved: Int)

/** Stateful per-PagingSource guard against repeated extension pages. */
class PageDeduplicator<T>(
    private val canonicalKey: (T) -> String,
    private val maxDuplicateOnlyPages: Int = 6,
) {
    private val seen = hashSetOf<String>()
    private var duplicateOnlyPages = 0

    fun accept(items: Iterable<T>, hasNextPage: Boolean): PageDeduplication<T> {
        val incoming = items.toList()
        val unique = incoming.filter { item ->
            val key = canonicalKey(item).trim()
            key.isNotEmpty() && seen.add(key)
        }
        duplicateOnlyPages = if (unique.isEmpty()) duplicateOnlyPages + 1 else 0
        return PageDeduplication(
            items = unique,
            hasNext = hasNextPage && duplicateOnlyPages < maxDuplicateOnlyPages,
            duplicatesRemoved = incoming.size - unique.size,
        )
    }
}
