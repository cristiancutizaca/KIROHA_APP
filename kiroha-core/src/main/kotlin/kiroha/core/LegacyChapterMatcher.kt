package kiroha.core

/** Matches files already known to belong to the same manga. */
class LegacyChapterMatcher(namesByUrl: Map<String, Set<String>>) {
    private val chapterUrls = namesByUrl.keys
    private val urlsByName = buildMap<String, MutableSet<String>> {
        namesByUrl.forEach { (url, names) ->
            names.forEach { name -> getOrPut(name) { mutableSetOf() }.add(url) }
        }
    }

    fun match(fileName: String, verifiedUrls: Set<String>, onlyDownload: Boolean): String? {
        verifiedUrls.singleOrNull()?.let { return it }

        val matchingNames = urlsByName[fileName].orEmpty()
        val candidates = if (verifiedUrls.isEmpty()) matchingNames else matchingNames.intersect(verifiedUrls)
        if (candidates.size == 1) return candidates.single()

        return chapterUrls.singleOrNull().takeIf { onlyDownload }
    }

    companion object {
        fun additionalDownloadCount(legacyMatched: Int, legacyUnmatched: Int, current: Int, overlapping: Int): Int {
            val remainingCurrent = current - overlapping
            return legacyMatched - overlapping + (legacyUnmatched - remainingCurrent).coerceAtLeast(0)
        }
    }
}
