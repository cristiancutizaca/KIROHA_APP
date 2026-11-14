package kiroha.core

import java.security.MessageDigest

/**
 * Stable, device-independent download identity. A human-readable title is NOT
 * an identity: separate galleries can have exactly the same name and chapter names.
 *
 * Never use local SQL row IDs: backup/restore and library sync may renumber them.
 */
object KirohaDownloadPaths {
    private const val HEX_DIGITS = "0123456789abcdef"
    private val sourceStorageIdSuffix = Regex("__s_-?[0-9]+$")
    private val mangaIdentitySuffix = Regex("__k_[0-9a-f]{16}$")

    fun hasSourceIdentity(folderName: String): Boolean = sourceStorageIdSuffix.containsMatchIn(folderName)

    fun hasMangaIdentity(folderName: String): Boolean = mangaIdentitySuffix.containsMatchIn(folderName)

    /**
     * Source folder aliases are read locations, not manga identities.
     * For example: E-Hentai (EN) and E-Hentai (EN)__s_57122881048805941
     * may both contain downloads for the same installed extension.
     */
    fun matchesSourceFolder(folderName: String, expectedLabel: String): Boolean =
        folderName.replace(sourceStorageIdSuffix, "").equals(expectedLabel, ignoreCase = true)

    /**
     * An old title-only directory can be considered for read-only recovery
     * only if its owner is unambiguous among favorites. An entry opened from
     * source browsing may use this fallback only if it has a single chapter
     * and there is no favorite with the same title. Files still need separate
     * page/metadata checks, and a gallery may claim a folder only once.
     */
    fun permitsLegacyTitleRecovery(
        favoriteOwnerUrls: Set<String>,
        mangaUrl: String,
        isFavorite: Boolean,
        chapterCount: Int,
        matchingChapterCount: Int,
    ): Boolean {
        if (mangaUrl.isBlank() || chapterCount <= 0 || matchingChapterCount != 1) return false
        if (favoriteOwnerUrls.size > 1 || favoriteOwnerUrls.any { it != mangaUrl }) return false
        return if (isFavorite) {
            favoriteOwnerUrls == setOf(mangaUrl)
        } else {
            favoriteOwnerUrls.isEmpty() && chapterCount == 1
        }
    }

    fun mangaSuffix(sourceId: Long, canonicalMangaUrl: String): String {
        require(canonicalMangaUrl.isNotBlank()) { "Canonical manga URL must not be blank" }
        val raw = "$sourceId\u0000$canonicalMangaUrl"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(20) {
            append("__k_")
            for (index in 0 until 8) {
                val byte = digest[index].toInt() and 0xff
                append(HEX_DIGITS[byte ushr 4])
                append(HEX_DIGITS[byte and 0x0f])
            }
        }
    }
}
