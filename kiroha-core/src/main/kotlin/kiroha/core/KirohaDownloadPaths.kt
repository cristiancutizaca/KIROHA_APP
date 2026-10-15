package kiroha.core

import java.security.MessageDigest

/**
 * Stable, device-independent download identity. A human-readable title is NOT
 * an identity: separate galleries can have exactly the same name and chapter names.
 *
 * Never use local SQL row IDs: backup/restore and library sync may renumber them.
 */
object KirohaDownloadPaths {
    private val sourceStorageIdSuffix = Regex("__s_[0-9]+$")

    /**
     * Source folder aliases are read locations, not manga identities.
     * For example: E-Hentai (EN) and E-Hentai (EN)__s_57122881048805941
     * may both contain downloads for the same installed extension.
     */
    fun matchesSourceFolder(folderName: String, expectedLabel: String): Boolean =
        folderName.replace(sourceStorageIdSuffix, "").equals(expectedLabel, ignoreCase = true)

    fun mangaSuffix(sourceId: Long, canonicalMangaUrl: String): String {
        require(canonicalMangaUrl.isNotBlank()) { "Canonical manga URL must not be blank" }
        val raw = "$sourceId\u0000$canonicalMangaUrl"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "__k_" + hex.take(16)
    }
}
