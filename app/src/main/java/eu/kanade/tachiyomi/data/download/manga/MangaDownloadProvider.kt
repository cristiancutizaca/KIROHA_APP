package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import android.util.Xml
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kiroha.core.KirohaDownloadPaths
import logcat.LogPriority
import org.xmlpull.v1.XmlPullParser
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

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
    private val downloadPreferences: DownloadPreferences,
) {

    private val downloadsDir: UniFile?
        get() = storageManager.getDownloadsDirectory()

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
        val mangaDir = findMangaDir(mangaTitle, source, mangaUrl)
        val current = getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl).asSequence()
            .mapNotNull { mangaDir?.findFile(it) }
            .firstOrNull()
        // New downloads keep their source ID + manga URL identity. Legacy folders
        // are only read after their embedded chapter URL proves ownership.
        return current ?: findVerifiedLegacyChapterDir(
            chapterName, chapterScanlator, chapterUrl, mangaTitle, source, mangaUrl,
        )
    }

    /**
     * Read-only fallback for the old downloads/<source label>/<title>/<chapter> layout.
     * Old title-only directories may contain several distinct galleries. Never rename,
     * delete, or claim the whole parent directory just because the title matches.
     *
     * MUST be invoked off the UI thread: legacy metadata is read from SAF storage.
     */
    fun findVerifiedLegacyChapterDir(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        source: MangaSource,
        mangaUrl: String?,
    ): UniFile? {
        if (mangaUrl.isNullOrBlank() || chapterUrl.isBlank()) return null
        val httpSource = source as? HttpSource ?: return null
        val expectedUrl = runCatching {
            httpSource.getChapterUrl(SChapter.create().apply {
                url = chapterUrl
                name = chapterName
                scanlator = chapterScanlator
            })
        }.getOrNull()?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null

        val root = downloadsDir ?: return null
        val legacySourceName = DiskUtil.buildValidFilename(
            source.toString(),
            DiskUtil.MAX_FILE_NAME_BYTES,
            libraryPreferences.disallowNonAsciiFilenames.get(),
        )
        val legacySourceDir = root.findFile(legacySourceName) ?: return null
        if (!legacySourceDir.isDirectory) return null
        val oldTitle = DiskUtil.buildValidFilename(
            mangaTitle,
            DiskUtil.MAX_FILE_NAME_BYTES,
            libraryPreferences.disallowNonAsciiFilenames.get(),
        )
        val legacyMangaDir = legacySourceDir.findFile(oldTitle) ?: return null
        if (!legacyMangaDir.isDirectory) return null

        val candidates = (getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl) +
            getLegacyChapterDirNames(chapterName, chapterScanlator, chapterUrl)
                .flatMap { listOf(it, "$it.cbz") }).distinct()

        return candidates.asSequence().mapNotNull(legacyMangaDir::findFile)
            .firstOrNull { file -> hasVerifiedLegacyComicInfo(file, expectedUrl) }
    }

    /** Read at most 512 KiB of metadata, never the actual manga images. */
    private fun hasVerifiedLegacyComicInfo(file: UniFile, expectedUrl: String): Boolean {
        return try {
            when {
                file.isDirectory -> {
                    val xml = file.findFile("ComicInfo.xml")
                    xml != null && xml.isFile && xml.length() in 1..MAX_LEGACY_XML_BYTES &&
                        xml.openInputStream().use { input -> comicInfoMatches(input, expectedUrl) }
                }
                // ZipFile reads the central directory without streaming a giant CBZ.
                // SAF/content:// CBZs without random access need manual recovery.
                file.isFile && file.name?.endsWith(".cbz", ignoreCase = true) == true &&
                    file.uri.scheme == "file" -> {
                    val path = file.uri.path ?: return false
                    ZipFile(File(path)).use { zip ->
                        val xml = zip.getEntry("ComicInfo.xml")
                        xml != null && xml.size in 1..MAX_LEGACY_XML_BYTES &&
                            zip.getInputStream(xml).use { input -> comicInfoMatches(input, expectedUrl) }
                    }
                }
                else -> false
            }
        } catch (_: Exception) {
            // A corrupt or unidentifiable file must NEVER be adopted by title alone.
            false
        }
    }

    private fun comicInfoMatches(input: java.io.InputStream, expectedUrl: String): Boolean {
        val parser = Xml.newPullParser()
        parser.setInput(input, Charsets.UTF_8.name())
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Web") {
                // Web can contain both tracking links and the chapter URL.
                val urls = parser.nextText().split(Regex("\\s+"))
                return urls.any { it.trim().trimEnd('/') == expectedUrl }
            }
            parser.next()
        }
        return false
    }

    private companion object {
        const val MAX_LEGACY_XML_BYTES = 512L * 1024
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
     * Returns the download directory name for a manga.
     *
     * @param mangaTitle the title of the manga to query.
     */
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

    /**
     * Returns list of names that might have been previously used as
     * the directory name for a chapter.
     * Add to this list if naming pattern ever changes.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query.
     * @param chapterUrl url of the chapter to query.
     */
    private fun getLegacyChapterDirNames(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
    ): List<String> {
        val sanitizedChapterName = sanitizeChapterName(chapterName)
        val chapterNameV1 = DiskUtil.buildValidFilename(
            when {
                !chapterScanlator.isNullOrBlank() -> "${chapterScanlator}_$sanitizedChapterName"
                else -> sanitizedChapterName
            },
        )

        // Get the filename that would be generated if the user were
        // using the other value for the disallow non-ASCII
        // filenames setting. This ensures that chapters downloaded
        // before the user changed the setting can still be found.
        val otherChapterDirName =
            getChapterDirName(
                chapterName,
                chapterScanlator,
                chapterUrl,
                disallowNonAsciiFilenames = !libraryPreferences.disallowNonAsciiFilenames.get(),
            )

        // Get the filename with the other value for including hash
        val otherHashChapterDirName =
            getChapterDirName(
                chapterName,
                chapterScanlator,
                chapterUrl,
                includeHash = !downloadPreferences.includeHashInDownloadFilenames.get(),
            )

        // Get the filename with both values inverted
        val otherBothChapterDirName =
            getChapterDirName(
                chapterName,
                chapterScanlator,
                chapterUrl,
                includeHash = !downloadPreferences.includeHashInDownloadFilenames.get(),
                disallowNonAsciiFilenames = !libraryPreferences.disallowNonAsciiFilenames.get(),
            )

        return buildList(4) {
            // Chapter name without hash (unable to handle duplicate
            // chapter names)
            add(chapterNameV1)
            add(otherChapterDirName)
            add(otherHashChapterDirName)
            add(otherBothChapterDirName)
        }
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
        // Never match a download by chapter title alone, or a different gallery with
        // a same-named chapter will appear downloaded. Old unhashed files remain
        // on disk for an explicit, identity-aware migration.
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
