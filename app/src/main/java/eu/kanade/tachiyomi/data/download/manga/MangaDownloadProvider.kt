package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kiroha.core.KirohaDownloadPaths
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * This class is used to provide the directories where the downloads should be saved.
 * It uses the following path scheme: /<root downloads dir>/<source name>/<manga>/<chapter>
 *
 * @param context the application context.
 */
@Inject
@SingleIn(AppScope::class)
class MangaDownloadProvider(
    private val context: Context,
    private val storageManager: StorageManager,
    private val libraryPreferences: LibraryPreferences,
) {

    private val downloadsDir: UniFile?
        get() = storageManager.getDownloadsDirectory()

    private data class LegacySourceFolders(
        val rootUri: String,
        val galleries: Map<String, List<UniFile>>,
    )

    private val legacyFolderIndex = ConcurrentHashMap<Long, LegacySourceFolders>()
    private var sourceFolders: Pair<String, List<UniFile>>? = null

    @Synchronized
    fun invalidateLegacyFolderIndex() {
        sourceFolders = null
        legacyFolderIndex.clear()
    }

    /**
     * Returns the download directory for a manga. For internal use only.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    internal fun getMangaDir(mangaTitle: String, source: MangaSource, mangaUrl: String): UniFile {
        try {
            return downloadsDir!!
                .createDirectory(getSourceDirName(source))!!
                .createDirectory(getMangaDirName(mangaTitle, mangaUrl, source.id))!!
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Invalid download directory" }
            throw Exception(
                context.stringResource(
                    MR.strings.invalid_location,
                    downloadsDir?.displayablePath ?: "",
                ),
            )
        }
    }

    /**
     * Returns the download directory for a source if it exists.
     *
     * @param source the source to query.
     */
    fun findSourceDir(source: MangaSource): UniFile? {
        return downloadsDir?.findFile(getSourceDirName(source))
    }

    /**
     * Returns the download directory for a manga if it exists.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    fun findMangaDir(mangaTitle: String, source: MangaSource, mangaUrl: String? = null): UniFile? {
        if (mangaUrl.isNullOrBlank()) return null
        val sourceDir = findSourceDir(source)
        return sourceDir?.findFile(getMangaDirName(mangaTitle, mangaUrl, source.id))
    }

    /**
     * Returns the download directory for a chapter if it exists.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param chapterUrl url of the chapter to query.
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the chapter.
     */
    fun findChapterDir(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        source: MangaSource,
        mangaUrl: String? = null,
    ): UniFile? {
        val mangaDir = findMangaDir(mangaTitle, source, mangaUrl) ?: return null
        return getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl)
            .firstNotNullOfOrNull(mangaDir::findFile)
    }

    fun isLegacyMangaTitleKnownAbsent(mangaTitle: String, sourceId: Long): Boolean {
        val folders = legacyFolderIndex[sourceId] ?: return false
        return legacyTitleNames(mangaTitle).none { it in folders.galleries }
    }

    internal fun legacyTitleNames(title: String): Set<String> = setOf(
        DiskUtil.buildValidFilename(title).lowercase(),
        DiskUtil.buildValidFilename(title, disallowNonAscii = true).lowercase(),
    )

    @Synchronized
    internal fun findLegacyMangaFolders(root: UniFile, manga: Manga, source: MangaSource): List<UniFile> {
        val rootUri = root.uri.toString()
        val index = legacyFolderIndex[source.id]?.takeIf { it.rootUri == rootUri }
            ?: indexLegacyMangaFolders(root, source).also { legacyFolderIndex[source.id] = it }
        val titles = legacyTitleNames(manga.title) + legacyTitleNames(manga.ogTitle)
        return titles.flatMap { index.galleries[it].orEmpty() }.distinctBy { it.uri }
    }

    private fun indexLegacyMangaFolders(root: UniFile, source: MangaSource): LegacySourceFolders {
        val rootUri = root.uri.toString()
        val sourceDirs = sourceFolders?.takeIf { it.first == rootUri }?.second
            ?: root.listFiles().orEmpty().filter { it.isDirectory && !it.name.isNullOrBlank() }
                .also { sourceFolders = rootUri to it }
        val sourceLabels = legacyTitleNames(source.toString()).toMutableSet()
        for (dir in sourceDirs) {
            val name = dir.name.orEmpty()
            if (KirohaDownloadPaths.sourceFolderId(name) == source.id) {
                // A restored source stub may have only an ID. The current folder
                // supplies the original label without guessing from another source.
                sourceLabels += name.substringBeforeLast("__s_").lowercase()
            }
        }
        val legacySourceDirs = sourceDirs.filter { dir ->
            val name = dir.name.orEmpty()
            val id = KirohaDownloadPaths.sourceFolderId(name)
            if (id == null) name.lowercase() in sourceLabels else id == source.id
        }
        val galleries = legacySourceDirs.asSequence().flatMap { sourceDir ->
            sourceDir.listFiles().orEmpty().asSequence().mapNotNull { gallery ->
                val name = gallery.name?.takeUnless(String::isBlank) ?: return@mapNotNull null
                if (KirohaDownloadPaths.hasMangaIdentity(name) || !gallery.isDirectory) return@mapNotNull null
                name.lowercase() to gallery
            }
        }.groupBy({ it.first }, { it.second })
        return LegacySourceFolders(rootUri, galleries)
    }

    /**
     * Returns a list of downloaded directories for the chapters that exist.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun findChapterDirs(chapters: List<Chapter>, manga: Manga, source: MangaSource): Pair<UniFile?, List<UniFile>> {
        val mangaDir = findMangaDir(manga.title, source, manga.url) ?: return null to emptyList()
        return mangaDir to chapters.mapNotNull { chapter ->
            getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).asSequence()
                .mapNotNull { mangaDir.findFile(it) }
                .firstOrNull()
        }
    }

    /**
     * Returns the download directory name for a source.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: MangaSource): String {
        // A display label alone is not an extension identity: multiple source IDs
        // can share a label, and their manga must never share cache ownership.
        val suffix = "__s_" + source.id
        val prefix = DiskUtil.buildValidFilename(
            source.toString(),
            DiskUtil.MAX_FILE_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size,
            libraryPreferences.disallowNonAsciiFilenames.get(),
        )
        return prefix + suffix
    }

    /**
     * Namespaced by source ID and canonical manga URL, not by title alone.
     * This prevents distinct galleries with the same displayed title from sharing files.
     * The title is only a readable prefix; the stable suffix owns the identity.
     *
     * Existing title-only directories are left untouched. They cannot be assigned to
     * a specific manga safely when several works share a title.
     */
    fun getMangaDirName(mangaTitle: String, mangaUrl: String, sourceId: Long): String {
        require(mangaUrl.isNotBlank()) { "A manga URL is required to identify downloads" }
        val suffix = KirohaDownloadPaths.mangaSuffix(sourceId, mangaUrl)
        val prefix = DiskUtil.buildValidFilename(
            mangaTitle,
            DiskUtil.MAX_FILE_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size,
            libraryPreferences.disallowNonAsciiFilenames.get(),
        )
        return prefix + suffix
    }

    /**
     * Returns the chapter directory name for a chapter.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query.
     * @param chapterUrl url of the chapter to query.
     */
    fun getChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        includeHash: Boolean = true,
        disallowNonAsciiFilenames: Boolean = libraryPreferences.disallowNonAsciiFilenames.get(),
    ): String {
        var dirName = sanitizeChapterName(chapterName)
        if (!chapterScanlator.isNullOrBlank()) {
            dirName = chapterScanlator + "_" + dirName
        }
        // Reserve 12 hex digits + underscore and the 4-byte .cbz extension.
        dirName = DiskUtil.buildValidFilename(dirName, DiskUtil.MAX_FILE_NAME_BYTES - 17, disallowNonAsciiFilenames)
        if (includeHash) {
            dirName += "_" + md5(chapterUrl).take(12)
        }
        return dirName
    }

    /** Recognizes the original, shortened and hashed chapter names written by Mihon and Aniyomi. */
    internal fun legacyChapterNames(chapter: Chapter): Set<String> {
        val name = sanitizeChapterName(chapter.name)
        val scanlator = chapter.scanlator?.takeUnless(String::isBlank)
        val combined = if (scanlator == null) name else "${scanlator}_$name"
        val hash = md5(chapter.url)
        return buildSet {
            for (asciiOnly in listOf(false, true)) {
                add(DiskUtil.buildValidFilename(combined, disallowNonAscii = asciiOnly))
                add(getChapterDirName(name, scanlator, chapter.url, false, asciiOnly))
                add(getChapterDirName(name, scanlator, chapter.url, true, asciiOnly))
                val mihonName = DiskUtil.buildValidFilename(combined, DiskUtil.MAX_FILE_NAME_BYTES - 11, asciiOnly)
                add(mihonName)
                add("${mihonName}_${hash.take(6)}")

                if (scanlator != null) {
                    val shortName = DiskUtil.buildValidFilename(name, DiskUtil.MAX_FILE_NAME_BYTES - 11, asciiOnly)
                    val prefixes = setOf(DiskUtil.buildValidFilename(scanlator), scanlator).filter { '/' !in it }
                    for (prefix in prefixes) {
                        add("${prefix}_$shortName")
                        add("${prefix}_${shortName}_${hash.take(6)}")
                    }
                }
            }
        }.mapTo(hashSetOf()) { it.lowercase() }
    }

    /**
     * Return the new name for the chapter (in case it's empty or blank)
     *
     * @param chapterName the name of the chapter
     */
    private fun sanitizeChapterName(chapterName: String): String {
        return chapterName.ifBlank {
            "Chapter"
        }
    }

    fun isChapterDirNameChanged(oldChapter: Chapter, newChapter: Chapter): Boolean {
        return getChapterDirName(oldChapter.name, oldChapter.scanlator, oldChapter.url) !=
            getChapterDirName(newChapter.name, newChapter.scanlator, newChapter.url)
    }

    /**
     * Returns valid downloaded chapter directory names.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query.
     * @param chapterUrl url of the chapter to query.
     */
    fun getValidChapterDirNames(chapterName: String, chapterScanlator: String?, chapterUrl: String): List<String> {
        // Only Kiroha's current naming scheme is used to write, delete or rename downloads.
        // Legacy paths discovered by the read index are kept separate from these operations.
        val names = listOf(
            getChapterDirName(chapterName, chapterScanlator, chapterUrl, includeHash = true),
            getChapterDirName(
                chapterName,
                chapterScanlator,
                chapterUrl,
                includeHash = true,
                disallowNonAsciiFilenames = !libraryPreferences.disallowNonAsciiFilenames.get(),
            ),
        ).distinct()
        return names.flatMap { listOf(it, "$it.cbz") }
    }
}
