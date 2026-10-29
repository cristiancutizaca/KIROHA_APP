package kiroha.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadPageFilesTest {
    @Test
    fun splitPagesAndMetadataMustNotCauseFalseMissingPages() {
        val files = listOf(
            "000.jpg", "001__001.webp", "001__002.webp", "002.png",
            "ComicInfo.xml", ".nomedia", "orphan.tmp",
        )
        assertEquals(emptyList<Int>(), DownloadPageFiles.missingPageNumbers(listOf(0, 1, 2), 3, files))
    }

    @Test
    fun temporaryFilesAreNotFinishedPages() {
        assertEquals(listOf(1), DownloadPageFiles.missingPageNumbers(
            listOf(0, 1, 2), 3, listOf("000.jpg", "001.tmp", "002.webp"),
        ))
    }

    @Test
    fun secondSplitPartAloneIsNotASuccessfulPage() {
        assertEquals(listOf(1), DownloadPageFiles.missingPageNumbers(
            listOf(0, 1), 2, listOf("000.jpg", "001__002.jpg"),
        ))
    }

    @Test
    fun filenameChecksAreConsistentAcrossExtensions() {
        assertTrue(DownloadPageFiles.isSavedImageFile("001__001.png", "001"))
        assertTrue(DownloadPageFiles.isSavedImageFile("001.webp", "001"))
        assertFalse(DownloadPageFiles.isSavedImageFile("001.tmp", "001"))
        assertFalse(DownloadPageFiles.isSavedImageFile("001__002.png", "001"))
    }
}
