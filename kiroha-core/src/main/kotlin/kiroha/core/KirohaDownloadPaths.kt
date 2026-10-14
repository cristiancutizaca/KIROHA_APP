package kiroha.core

import java.security.MessageDigest

/**
 * Stable, device-independent download identity. A human-readable title is NOT
 * an identity: separate galleries can have exactly the same name and chapter names.
 *
 * Never use local SQL row IDs: backup/restore and library sync may renumber them.
 */
object KirohaDownloadPaths {
    fun mangaSuffix(sourceId: Long, canonicalMangaUrl: String): String {
        require(canonicalMangaUrl.isNotBlank()) { "Canonical manga URL must not be blank" }
        val raw = "$sourceId\u0000$canonicalMangaUrl"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "__k_" + hex.take(16)
    }
}
