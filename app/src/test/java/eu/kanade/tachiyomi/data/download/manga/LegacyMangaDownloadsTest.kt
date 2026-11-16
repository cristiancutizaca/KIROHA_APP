package eu.kanade.tachiyomi.data.download.manga

import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.Hash
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.manga.model.StubMangaSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LegacyMangaDownloadsTest {

    @TempDir
    lateinit var downloads: Path

    private val preferences = mockk<LibraryPreferences> {
        every { disallowNonAsciiFilenames.get() } returns false
    }
    private val provider = MangaDownloadProvider(mockk(), mockk(), preferences)
    private val scanner = LegacyMangaDownloadScanner(mockk(), provider)
    private val manga = Manga.create().copy(ogTitle = "Manga Original", source = 42, url = "/series/a")
    private val source = StubMangaSource(manga.source, "en", "Example")
    private val first = Chapter.create().copy(name = "Capítulo 1", url = "/series/a/1")
    private val second = Chapter.create().copy(name = "Capítulo 2", url = "/series/a/2", scanlator = "Group")

    @Test
    fun findsTheOldFolderAlongsideANewNamespacedFolderUsingTheSourcesStoredId() {
        val oldMangaDir = directory(manga.ogTitle, cbz("Capítulo 1.cbz"))
        val newMangaDir = directory(provider.getMangaDirName(manga.title, manga.url, manga.source))
        val oldSourceDir = directory("Example (EN)", oldMangaDir)
        val newSourceDir = directory("Example (EN)__s_42", newMangaDir)
        val otherSourceDir = directory("Example (EN)__s_77", directory(manga.ogTitle))
        val root = directory("downloads", oldSourceDir, newSourceDir, otherSourceDir)
        val restoredStub = StubMangaSource(manga.source, "", "")

        val legacy = provider.findLegacyMangaFolders(root, manga, restoredStub)

        assertEquals(1, legacy.size)
        assertSame(oldMangaDir, legacy.single())
    }

    @Test
    fun countsAnOldCbzEvenWhenTheRestoredLibraryLacksChapterRows() = runTest {
        val archive = cbz("Capítulo 1.cbz")
        val incompleteFolder = mockk<UniFile> {
            every { name } returns "Capítulo 2_tmp"
            every { isDirectory } returns true
        }
        val empty = cbz("Capítulo 3.cbz", withPage = false)
        val folder = folder(archive, incompleteFolder, empty)

        val result = scanner.scan(manga, source, emptyList(), listOf(folder), setOf(folder.uri))

        assertEquals(1, result.additionalCount(emptySet()))
        assertEquals(0, result.files.size)
    }

    @Test
    fun readsMihonChapterHashesAndDoesNotCountTheKirohaCopyTwice() = runTest {
        val legacyHash = Hash.md5(second.url).take(6)
        val archive = cbz("Group_Capítulo 2_$legacyHash.cbz")
        val firstFile = cbz("Capítulo 1.cbz")
        val folder = folder(firstFile, archive)

        val result = scanner.scan(manga, source, listOf(first, second), listOf(folder), setOf(folder.uri))

        assertSame(archive, result.files[second.url]?.file)
        assertSame(firstFile, result.files[first.url]?.file)
        val currentName = provider.getChapterDirName(second.name, second.scanlator, second.url).lowercase()
        assertEquals(1, result.additionalCount(setOf(currentName)))
    }

    @Test
    fun titleOnlyFilesInAnAmbiguousFolderRemainUnassigned() = runTest {
        val folder = folder(cbz("Capítulo 1.cbz"))

        val result = scanner.scan(manga, source, listOf(first), listOf(folder), emptySet())

        assertEquals(0, result.additionalCount(emptySet()))
        assertEquals(0, result.files.size)
    }

    private fun cbz(name: String, withPage: Boolean = true): UniFile {
        val path = downloads.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { archive ->
            if (withPage) {
                archive.putNextEntry(ZipEntry("001.jpg"))
                archive.write(byteArrayOf(1, 2, 3))
                archive.closeEntry()
            }
        }
        val uri = mockk<Uri> {
            every { scheme } returns "file"
            every { this@mockk.path } returns path.toString()
        }
        return mockk {
            every { this@mockk.name } returns name
            every { this@mockk.uri } returns uri
            every { isDirectory } returns false
            every { isFile } returns true
            every { length() } returns Files.size(path)
        }
    }

    private fun folder(vararg entries: UniFile): UniFile = directory(manga.ogTitle, *entries)

    private fun directory(folderName: String, vararg entries: UniFile): UniFile {
        val folderUri = mockk<Uri>()
        return mockk {
            every { uri } returns folderUri
            every { name } returns folderName
            every { isDirectory } returns true
            every { listFiles() } returns arrayOf(*entries)
        }
    }
}
