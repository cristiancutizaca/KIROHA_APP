package kiroha.core

data class PageDeduplication<T>(val items: List<T>, val hasNext: Boolean, val duplicatesRemoved: Int)

/** Stateful per-PagingSource guard against repeated extension pages. */
class PageDeduplicator<T>(
    private val canonicalKey: (T) -> String,
    private val maxDuplicateOnlyPages: Int = 2,
) {
    private val seen = hashSetOf<String>()
    private var duplicateOnlyPages = 0

    fun accept(items: Iterable<T>, hasNextPage: Boolean): PageDeduplication<T> {
        val incoming = items.toList()
        val unique = incoming.filter { item -> canonicalKey(item).trim().isNotEmpty() && seen.add(canonicalKey(item)) }
        duplicateOnlyPages = if (unique.isEmpty()) duplicateOnlyPages + 1 else 0
        return PageDeduplication(
            items = unique,
            hasNext = hasNextPage && duplicateOnlyPages < maxDuplicateOnlyPages,
            duplicatesRemoved = incoming.size - unique.size,
        )
    }
}
