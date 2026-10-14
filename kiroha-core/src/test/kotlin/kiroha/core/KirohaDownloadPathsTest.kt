package kiroha.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

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
    fun emptyUrlIsNotAValidDownloadIdentity() {
        assertFailsWith<IllegalArgumentException> {
            KirohaDownloadPaths.mangaSuffix(100L, "")
        }
    }
}
