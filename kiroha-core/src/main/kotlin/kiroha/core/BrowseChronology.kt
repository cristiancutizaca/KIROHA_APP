package kiroha.core

/**
 * Best-effort ordering of already loaded extension browse and search results.
 * Neither SAnime nor SManga exposes a standard publication timestamp.
 */
object BrowseChronology {
    private val yearPattern = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")

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
