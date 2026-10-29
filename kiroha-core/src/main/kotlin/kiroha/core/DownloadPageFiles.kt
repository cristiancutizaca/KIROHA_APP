package kiroha.core

import java.util.Locale

/**
 * Validates the files for each logical manga page, not the raw number of files.
 * A tall image can be split into multiple files, and the temp folder can also
 * contain XML metadata or incomplete .tmp downloads.
 */
object DownloadPageFiles {
    fun isSavedImageFile(fileName: String, pagePrefix: String): Boolean =
        !fileName.endsWith(".tmp", ignoreCase = true) && (
            fileName.startsWith("$pagePrefix.") ||
                fileName.startsWith("${pagePrefix}__001.")
            )

    fun missingPageNumbers(
        pageNumbers: List<Int>,
        pageCount: Int,
        fileNames: Iterable<String>,
    ): List<Int> {
        require(pageCount > 0) { "Page count must be positive" }
        val savedPrefixes = fileNames.asSequence()
            .mapNotNull { name ->
                val prefix = name.substringBefore('.').substringBefore("__")
                prefix.takeIf {
                    it.isNotEmpty() && it.all(Char::isDigit) && isSavedImageFile(name, it)
                }
            }
            .toHashSet()

        val digits = pageCount.toString().length.coerceAtLeast(3)
        return pageNumbers.filterNot { page ->
            "%0${digits}d".format(Locale.ENGLISH, page) in savedPrefixes
        }
    }
}
