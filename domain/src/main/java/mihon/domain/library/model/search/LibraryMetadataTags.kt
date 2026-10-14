package mihon.domain.library.model.search

/**
 * Searches tag namespaces exposed by gallery extensions (for example E-Hentai).
 *
 * Some sources store tags as genres while others only place them inside the
 * description, e.g. "parody: <grand blue>" or "character: <someone>".
 * Only already-saved library metadata is searched; no network requests are made.
 */
object LibraryMetadataTags {

    private val metadataLine = Regex(
        """(?m)^[ \t]*(?:[-*•▪·]\s*)?([a-zA-Z][a-zA-Z _-]{0,30})\s*:[ \t]*([^\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val tagCategories = setOf(
        "parody",
        "character",
        "female",
        "male",
        "mixed",
        "other",
        "group",
        "artist",
        "cosplayer",
        "language",
        "reclass",
        "circle",
        "series",
        "genre",
        "tag",
        "tags",
    )

    /**
     * A null category searches all tag categories. "tag" and "genre" also search
     * plain genres; a specific category requires a matching metadata label so
     * character: cannot accidentally return an entry tagged only as parody:.
     */
    fun matches(
        value: String,
        category: String? = null,
        genres: List<String> = emptyList(),
        descriptions: List<String?> = emptyList(),
    ): Boolean {
        if (value.isBlank()) return false

        val namespace = category?.lowercase()
        val allTags = namespace == null || namespace == "tag" || namespace == "genre"

        if (allTags && genres.any { matchesTagValue(it, value) }) {
            return true
        }

        return (genres.asSequence() + descriptions.asSequence().filterNotNull()).any { text ->
            metadataLine.findAll(text).any { match ->
                val label = match.groupValues[1].lowercase().trim()
                val isAllowed = if (allTags) label in tagCategories else label == namespace

                isAllowed && matchesTagValue(match.groupValues[2], value)
            }
        }
    }
    private fun matchesTagValue(text: String, value: String): Boolean {
        if (text.contains(value, ignoreCase = true)) return true

        // Some extensions preserve gallery tag slugs with underscores or hyphens.
        fun normalize(input: String) = input.replace('_', ' ').replace('-', ' ')
        return normalize(text).contains(normalize(value), ignoreCase = true)
    }

}
