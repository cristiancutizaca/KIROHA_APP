package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import android.util.Xml
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kiroha.core.KirohaDownloadPaths
import logcat.LogPriority
import mihon.core.archive.archiveReader
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
import java.util.concurrent.ConcurrentHashMap
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

    // A legacy source may contain hundreds of title folders. Index it once in
    // memory so checking each chapter does not enumerate the same directory
    // hundreds of times. This index never moves or rewrites user files.
    private data class LegacySourceFolders(
        val rootUri: String,
        val folders: Map<String, List<UniFile>>,
        val unnamespacedFolders: Map<String, List<UniFile>>,
    )
    private val legacyFolderIndex = ConcurrentHashMap<Long, LegacySourceFolders>()

    // A title-only folder cannot identify the gallery by itself. A recovered
    // single-chapter download is shared with the reader only AFTER the cache
    // has checked that this is the sole matching item in the user's library.
    private data class RecoveredChapterKey(val sourceId: Long, val mangaUrl: String, val chapterUrl: String)
    private data class RecoveredChapter(val rootUri: String, val file: UniFile)
    private val recoveredChapters = ConcurrentHashMap<RecoveredChapterKey, RecoveredChapter>()
    private val claimedGalleryOwners = ConcurrentHashMap<String, String>()

    fun invalidateLegacyFolderIndex() {
        legacyFolderIndex.clear()
        recoveredChapters.clear()
        claimedGalleryOwners.clear()
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
        val mangaDir = findMangaDir(mangaTitle, source, mangaUrl)
        val current = getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl).asSequence()
            .mapNotNull { mangaDir?.findFile(it) }
            .firstOrNull()
        // The async cache may have safely associated an old title-only folder
        // with this specific URL. Reuse that association in the reader.
        val claim = mangaUrl?.let { recoveredChapters[RecoveredChapterKey(source.id, it, chapterUrl)] }
        val recovered = claim?.takeIf { it.rootUri == downloadsDir?.uri?.toString() }
            ?.file?.takeIf { it.exists() }
        return current ?: recovered ?: findVerifiedLegacyChapterDir(
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
        val root = downloadsDir ?: return null

        // E-Hentai (EN) and E-Hentai (EN)__s_<id> are READ aliases.
        // Their labels only choose where to search. Each chapter MUST prove
        // its URL identity before a gallery can claim those existing files.
        // The user may have changed the non-ASCII filename setting since
        // downloading. Both historical sanitized names are valid read aliases.
        val oldTitles = listOf(
            libraryPreferences.disallowNonAsciiFilenames.get(),
            !libraryPreferences.disallowNonAsciiFilenames.get(),
        ).map { asciiOnly ->
            DiskUtil.buildValidFilename(mangaTitle, DiskUtil.MAX_FILE_NAME_BYTES, asciiOnly)
        }.distinct()
        val candidates = (getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl) +
            getLegacyChapterDirNames(chapterName, chapterScanlator, chapterUrl)
                .flatMap { listOf(it, "$it.cbz") }).distinct()
        val chapterUrlHash = "_" + md5(chapterUrl).take(12)

        // Old ComicInfo may contain the gallery URL instead of the chapter URL.
        val expectedUrls = buildSet {
            add(chapterUrl.trim().trimEnd('/'))
            add(mangaUrl.trim().trimEnd('/'))
            runCatching {
                httpSource.getChapterUrl(SChapter.create().apply {
                    url = chapterUrl
                    name = chapterName
                    scanlator = chapterScanlator
                })
            }.getOrNull()?.trim()?.trimEnd('/')?.let { add(it) }
            runCatching {
                httpSource.getMangaUrl(SManga.create().apply {
                    url = mangaUrl
                    title = mangaTitle
                })
            }.getOrNull()?.trim()?.trimEnd('/')?.let { add(it) }
        }.filter { it.isNotBlank() }.toSet()

        val sourceFolders = legacySourceFolders(root, source)
        return oldTitles.asSequence()
            .flatMap { sourceFolders[it].orEmpty().asSequence() }
            .flatMap { legacyMangaDir ->
                candidates.asSequence().mapNotNull(legacyMangaDir::findFile)
            }
            .firstOrNull { file ->
                // Exact chapter hash OR matching metadata. No title-only claims.
                val withoutExtension = file.name.orEmpty().removeSuffix(".cbz")
                val hashedChapter = withoutExtension.endsWith(chapterUrlHash) &&
                    if (file.isDirectory) {
                        file.listFiles().orEmpty().any {
                            it.isFile && it.length() > 0L &&
                                it.name != "ComicInfo.xml" && it.name != ".nomedia" &&
                                it.name?.endsWith(".tmp") != true
                        }
                    } else {
                        file.isFile && file.length() > 512L
                    }
                hashedChapter || hasVerifiedLegacyComicInfo(file, expectedUrls)
            }
    }

    /**
     * Read-only recovery of legacy title-only galleries when there is only ONE
     * matching favorited manga for this source. The cache verifies ownership
     * against the library and known chapters before invoking this.
     * For multi-chapter works the chapter filename must also match. In a
     * single-chapter gallery, the only image-bearing child is sufficient even
     * when its old chapter label has changed.
     *
     * Never infer ownership from a newer namespaced source directory.
     */
    fun findUniqueLegacyChapterDir(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        source: MangaSource,
        mangaUrl: String,
        allowUnnamedSingleChapter: Boolean,
    ): UniFile? {
        if (mangaUrl.isBlank() || chapterUrl.isBlank() || source !is HttpSource) return null
        val root = downloadsDir ?: return null
        val titleVariants = listOf(
            libraryPreferences.disallowNonAsciiFilenames.get(),
            !libraryPreferences.disallowNonAsciiFilenames.get(),
        ).map { asciiOnly ->
            DiskUtil.buildValidFilename(mangaTitle, DiskUtil.MAX_FILE_NAME_BYTES, asciiOnly)
        }.distinct()

        val folderIndex = legacySourceFolders(root, source, unnamespacedOnly = true)
        val galleries = titleVariants.flatMap { folderIndex[it].orEmpty() }
            .distinctBy { it.uri.toString() }
        // Duplicate title folders, even across old source roots, are ambiguous.
        if (galleries.size != 1) return null
        val gallery = galleries.single()
        val chapters = gallery.listFiles().orEmpty().filter {
            it.isDirectory || (it.isFile && it.name?.endsWith(".cbz", ignoreCase = true) == true)
        }
        // A distinct old chapter name can be associated without XML if the
        // parent title belongs to one gallery in the saved library.
        val acceptedNames = (getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl) +
            getLegacyChapterDirNames(chapterName, chapterScanlator, chapterUrl)
                .flatMap { listOf(it, "$it.cbz") }).toSet()
        val named = chapters.filter { it.name in acceptedNames }
        val candidate = when {
            named.size == 1 -> named.single()
            named.isEmpty() && allowUnnamedSingleChapter && chapters.size == 1 -> chapters.single()
            else -> return null
        }
        if (!containsUsableLegacyPages(candidate)) return null

        val ownershipKey = root.uri.toString() + "\u0000" + source.id + "\u0000" + gallery.uri.toString()
        val existingOwner = claimedGalleryOwners.putIfAbsent(ownershipKey, mangaUrl)
        if (existingOwner != null && existingOwner != mangaUrl) return null
        recoveredChapters[RecoveredChapterKey(source.id, mangaUrl, chapterUrl)] =
            RecoveredChapter(root.uri.toString(), candidate)
        return candidate
    }

    private fun containsUsableLegacyPages(file: UniFile): Boolean {
        if (file.isFile) {
            if (file.name?.endsWith(".cbz", ignoreCase = true) != true || file.length() <= 512L) {
                return false
            }
            // If explicit identity metadata exists it MUST match via the
            // verified recovery path; never override a conflicting ComicInfo.
            return try {
                if (file.uri.scheme == "file") {
                    ZipFile(File(file.uri.path ?: return false)).use { zip ->
                        zip.getEntry("ComicInfo.xml") == null
                    }
                } else {
                    file.archiveReader(context).use { reader ->
                        reader.getInputStream("ComicInfo.xml")?.use { false } ?: true
                    }
                }
            } catch (_: Exception) {
                false
            }
        }
        if (!file.isDirectory || file.findFile("ComicInfo.xml") != null) return false
        return file.listFiles().orEmpty().any { page ->
            page.isFile && page.length() > 0L &&
                page.name.orEmpty().substringAfterLast('.', "").lowercase() in LEGACY_IMAGE_EXTENSIONS
        }
    }

    private fun legacySourceFolders(
        root: UniFile,
        source: MangaSource,
        unnamespacedOnly: Boolean = false,
    ): Map<String, List<UniFile>> {
        val rootUri = root.uri.toString()
        legacyFolderIndex[source.id]?.takeIf { it.rootUri == rootUri }?.let {
            return if (unnamespacedOnly) it.unnamespacedFolders else it.folders
        }

        val expectedLabels = listOf(
            libraryPreferences.disallowNonAsciiFilenames.get(),
            !libraryPreferences.disallowNonAsciiFilenames.get(),
        ).map { asciiOnly ->
            DiskUtil.buildValidFilename(source.toString(), DiskUtil.MAX_FILE_NAME_BYTES, asciiOnly)
        }.distinct()
        // The numeric __s_ suffix identifies the source-storage namespace,
        // but the visible extension label remains the same.
        val sourceDirs = root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.isNullOrBlank() }
            .filter { dir ->
                expectedLabels.any { label ->
                    KirohaDownloadPaths.matchesSourceFolder(dir.name.orEmpty(), label)
                }
            }
            .sortedWith(compareBy<UniFile> { it.name !in expectedLabels }.thenBy { it.name.orEmpty() })

        val entries = sourceDirs.flatMap { sourceDir ->
            sourceDir.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.isNullOrBlank() }
                .map { Triple(sourceDir.name in expectedLabels, it.name!!, it) }
        }
        val found = entries.groupBy(keySelector = { it.second }, valueTransform = { it.third })
        val oldOnly = entries.filter { it.first }
            .groupBy(keySelector = { it.second }, valueTransform = { it.third })
        legacyFolderIndex[source.id] = LegacySourceFolders(rootUri, found, oldOnly)
        return if (unnamespacedOnly) oldOnly else found
    }

    /** Read at most 512 KiB of metadata, never the actual manga images. */
    private fun hasVerifiedLegacyComicInfo(file: UniFile, expectedUrls: Set<String>): Boolean {
        return try {
            when {
                file.isDirectory -> {
                    val xml = file.findFile("ComicInfo.xml")
                    xml != null && xml.isFile && xml.length() in 1..MAX_LEGACY_XML_BYTES &&
                        xml.openInputStream().use { input -> comicInfoMatches(input, expectedUrls) }
                }
                file.isFile && file.name?.endsWith(".cbz", ignoreCase = true) == true -> {
                    // Android document providers expose content:// URIs, not real file
                    // paths. Use the existing archive reader (mmap + libarchive):
                    // it can read ComicInfo.xml without copying a multi-GB CBZ to
                    // temporary storage.
                    if (file.uri.scheme == "file") {
                        val path = file.uri.path ?: return false
                        ZipFile(File(path)).use { zip ->
                            val xml = zip.getEntry("ComicInfo.xml")
                            xml != null && xml.size in 1..MAX_LEGACY_XML_BYTES &&
                                zip.getInputStream(xml).use { input -> comicInfoMatches(input, expectedUrls) }
                        }
                    } else {
                        file.archiveReader(context).use { reader ->
                            reader.getInputStream("ComicInfo.xml")?.use { input ->
                                // A broken or hostile archive must not make us
                                // decode unbounded XML into memory.
                                val buffer = ByteArray(8192)
                                val xml = java.io.ByteArrayOutputStream()
                                while (xml.size() <= MAX_LEGACY_XML_BYTES) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    xml.write(buffer, 0, read)
                                }
                                xml.size() in 1..MAX_LEGACY_XML_BYTES &&
                                    java.io.ByteArrayInputStream(xml.toByteArray()).use { bytes ->
                                        comicInfoMatches(bytes, expectedUrls)
                                    }
                            } ?: false
                        }
                    }
                }
                else -> false
            }
        } catch (_: Exception) {
            // A corrupt or unidentifiable file must NEVER be adopted by title alone.
            false
        }
    }

    private fun comicInfoMatches(input: java.io.InputStream, expectedUrls: Set<String>): Boolean {
        val parser = Xml.newPullParser()
        parser.setInput(input, Charsets.UTF_8.name())
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Web") {
                // Web can contain both tracking links and the chapter URL.
                val urls = parser.nextText().split(Regex("\\s+"))
                return urls.any { it.trim().trimEnd('/') in expectedUrls }
            }
            parser.next()
        }
        return false
    }

    private companion object {
        const val MAX_LEGACY_XML_BYTES = 512L * 1024
        val LEGACY_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "jxl", "heic", "heif",
        )
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
