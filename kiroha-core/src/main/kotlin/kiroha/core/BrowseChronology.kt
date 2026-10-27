package kiroha.core

/**
 * Best-effort ordering of already loaded extension browse and search results.
 * Neither SAnime nor SManga exposes a standard publication timestamp.
 */
object BrowseChronology {
    private val yearPattern = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")

    /**
     * Reorder only the window Paging has loaded. Placeholder positions keep
     * their indexes, so date ordering can work before an entire listing loads.
     * Results may move as further pages arrive; sources must provide actual
     * publication dates to guarantee a global chronological ordering.
     */
    fun loadedWindowIndices(
        totalCount: Int,
        placeholdersBefore: Int,
        loadedTitles: List<String>,
    ): List<Int>? {
        if (totalCount <= 1 || placeholdersBefore < 0 ||
            placeholdersBefore > totalCount ||
            loadedTitles.size > totalCount - placeholdersBefore
        ) return null

        val order = newestFirstIndices(loadedTitles) ?: return null
        val end = placeholdersBefore + loadedTitles.size
        return List(totalCount) { index ->
            if (index >= placeholdersBefore && index < end) {
                placeholdersBefore + order[index - placeholdersBefore]
            } else {
                index
            }
        }
    }

    fun yearInTitle(title: String): Int? =
        yearPattern.findAll(title).lastOrNull()?.value?.toIntOrNull()

    /**
     * Sorted source indexes, descending by explicit year in title.
     * Null means keep source order. This avoids scrambling undated listings.
     * Ties and unknown-year items retain their original relative order.
     * Entries without an explicit year are placed after dated entries.
     */
    fun newestFirstIndices(titles: List<String>): List<Int>? {
        if (titles.size < 2) return null
        val years = titles.map(::yearInTitle)
        val known = years.count { it != null }
        if (known < 2) return null

        val sorted = titles.indices.sortedWith(
            compareByDescending<Int> { years[it] ?: Int.MIN_VALUE }
                .thenBy { it },
        )
        return sorted.takeUnless { indices ->
            indices.withIndex().all { (index, sourceIndex) -> index == sourceIndex }
        }
    }
}
