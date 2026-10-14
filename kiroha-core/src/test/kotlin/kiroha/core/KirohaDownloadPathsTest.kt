package kiroha.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KirohaDownloadPathsTest {
    @Test
    fun sameTitleDifferentUrlsCannotShareTheDownloadDirectory() {
        val first = KirohaDownloadPaths.mangaSuffix(100L, "/g/123/gallery")
        val second = KirohaDownloadPaths.mangaSuffix(100L, "/g/456/gallery")
        assertNotEquals(first, second)
        assertEquals(first, KirohaDownloadPaths.mangaSuffix(100L, "/g/123/gallery"))
    }

    @Test
    fun sameUrlFromTwoDifferentSourcesCannotShareTheDownloadDirectory() {
        assertNotEquals(
            KirohaDownloadPaths.mangaSuffix(100L, "/gallery/123"),
            KirohaDownloadPaths.mangaSuffix(200L, "/gallery/123"),
        )
    }

    @Test
    fun chaptersFromDifferentMangaWithSameRelativeChapterUrlAreNotEqual() {
        assertNotEquals(
            KirohaIdentity.mangaChapter("100", "/series/1", "/chapter/1"),
            KirohaIdentity.mangaChapter("100", "/series/2", "/chapter/1"),
        )
    }

    @Test
    fun emptyUrlIsNotAValidDownloadIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            KirohaDownloadPaths.mangaSuffix(100L, "")
        }
    }
}
