package eu.kanade.tachiyomi.data.download.manga

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

class LegacyMangaIndexTest {
    private val key = LegacyMangaIndex.Key(42, "/manga/abc")
    private val gallery = LegacyMangaIndex.LibraryEntry("Manga", "Manga", 2, 300)
    private val folderUri = "file:///downloads/Manga"
    private val folder = LegacyMangaIndex.Folder(lastModified = 400, mayMatchByTitle = true)
    private val stamps = mapOf(folderUri to folder)
    private val record = LegacyMangaIndex.Record(
        libraryEntry = gallery,
        folders = stamps,
        verifiedAt = 500,
        chapterUrls = setOf("/chapter/one", "/chapter/two"),
        files = mapOf(
            "/chapter/one" to LegacyMangaDownloads.Download(
                "file:///downloads/Manga/Chapter%20One.cbz",
                setOf("chapter one_x"),
            ),
        ),
        unmatchedCount = 0,
    )

    @Test
    fun reusesVerifiedDownloadsAfterRestartAndKeepsDuplicateCounts() {
        val original = LegacyMangaIndex.Snapshot("file:///downloads", mapOf(key to record))
        val reloaded = ProtoBuf.decodeFromByteArray<LegacyMangaIndex.Snapshot>(ProtoBuf.encodeToByteArray(original))
        assertEquals(original, reloaded)
        val saved = reloaded.records.getValue(key)
        assertTrue(saved.canReuse(gallery, stamps, 1000))
        assertEquals(1, saved.restoreDownloads().additionalCount(emptySet()))
        assertEquals(0, saved.restoreDownloads().additionalCount(setOf("chapter one_x")))
    }

    @Test
    fun rechecksWhenTheLibraryOrFolderOwnershipChangedOrTheRecordExpired() {
        assertFalse(record.canReuse(gallery.copy(chapterFetchedAt = 301), stamps, 1000))
        assertFalse(record.canReuse(gallery, mapOf(folderUri to folder.copy(lastModified = 401)), 1000))
        assertFalse(record.canReuse(gallery, mapOf(folderUri to folder.copy(mayMatchByTitle = false)), 1000))
        assertFalse(record.canReuse(gallery, emptyMap(), 1000))
        assertFalse(record.canReuse(gallery, stamps, record.verifiedAt + 25.hours.inWholeMilliseconds))
    }
}
