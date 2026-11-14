package kiroha.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KirohaDownloadPathsTest {
    @Test
    fun usesTheSameSuffixAsEarlierKirohaVersions() {
        assertEquals("__k_1b543f7a4adc3b09", KirohaDownloadPaths.mangaSuffix(100L, "/g/123/gallery"))
    }

    @Test
    fun classifiesFolderNamesWithoutTreatingDigitsInTitlesAsAnIdentity() {
        assertFalse(KirohaDownloadPaths.hasSourceIdentity("E-Hentai (EN)"))
        assertTrue(KirohaDownloadPaths.hasSourceIdentity("E-Hentai (EN)__s_57122881048805941"))
        assertTrue(KirohaDownloadPaths.matchesSourceFolder("E-Hentai (EN)__s_-1234", "E-Hentai (EN)"))
        assertFalse(KirohaDownloadPaths.hasMangaIdentity("Chapter 123"))
        assertTrue(KirohaDownloadPaths.hasMangaIdentity("Chapter 123__k_517809debb9c79d2"))
    }

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

    @Test
    fun oldAndNumericSourceFolderNamesAreReadAliases() {
        val displayName = "E-Hentai (EN)"
        assertTrue(KirohaDownloadPaths.matchesSourceFolder("E-Hentai (EN)", displayName))
        assertTrue(
            KirohaDownloadPaths.matchesSourceFolder(
                "E-Hentai (EN)__s_57122881048805941",
                displayName,
            ),
        )
        assertTrue(KirohaDownloadPaths.matchesSourceFolder("e-hentai (en)__s_1234", displayName))
    }

    @Test
    fun oneChapterGalleryOpenedOutsideTheLibraryCanUseUnclaimedLegacyFolder() {
        assertTrue(
            KirohaDownloadPaths.permitsLegacyTitleRecovery(
                favoriteOwnerUrls = emptySet(),
                mangaUrl = "/g/123/gallery",
                isFavorite = false,
                chapterCount = 1,
                matchingChapterCount = 1,
            ),
        )
    }

    @Test
    fun multiChapterUnfavoritedGalleryCannotClaimTitleOnlyLegacyFolder() {
        assertFalse(
            KirohaDownloadPaths.permitsLegacyTitleRecovery(
                favoriteOwnerUrls = emptySet(),
                mangaUrl = "/g/123/gallery",
                isFavorite = false,
                chapterCount = 2,
                matchingChapterCount = 1,
            ),
        )
    }

    @Test
    fun sameTitleWithOtherOrMultipleFavoritesBlocksUnverifiedRecovery() {
        for (owners in listOf(setOf("/g/other"), setOf("/g/123/gallery", "/g/other"))) {
            assertFalse(
                KirohaDownloadPaths.permitsLegacyTitleRecovery(
                    favoriteOwnerUrls = owners,
                    mangaUrl = "/g/123/gallery",
                    isFavorite = true,
                    chapterCount = 1,
                    matchingChapterCount = 1,
                ),
            )
        }
    }

    @Test
    fun uniqueFavoriteAndMatchingChapterCanRecoverAnOldDirectory() {
        assertTrue(
            KirohaDownloadPaths.permitsLegacyTitleRecovery(
                favoriteOwnerUrls = setOf("/g/123/gallery"),
                mangaUrl = "/g/123/gallery",
                isFavorite = true,
                chapterCount = 3,
                matchingChapterCount = 1,
            ),
        )
        assertFalse(
            KirohaDownloadPaths.permitsLegacyTitleRecovery(
                favoriteOwnerUrls = setOf("/g/123/gallery"),
                mangaUrl = "/g/123/gallery",
                isFavorite = true,
                chapterCount = 3,
                matchingChapterCount = 0,
            ),
        )
    }

    @Test
    fun neverMergeUnrelatedSourceFoldersByApproximateName() {
        val displayName = "E-Hentai (EN)"
        assertFalse(KirohaDownloadPaths.matchesSourceFolder("Hitomi (ES)__s_1234", displayName))
        assertFalse(KirohaDownloadPaths.matchesSourceFolder("E-Hentai (EN)__s_notanumber", displayName))
        assertFalse(KirohaDownloadPaths.matchesSourceFolder("E-Hentai (EN) (1)", displayName))
    }
}
