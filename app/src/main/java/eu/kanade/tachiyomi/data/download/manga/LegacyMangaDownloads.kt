package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kiroha.core.LegacyChapterMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import mihon.core.archive.archiveReader
import org.xmlpull.v1.XmlPullParser
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipFile
import kotlin.coroutines.CoroutineContext

internal data class LegacyMangaDownloads(
    val chapterUrls: Set<String>,
    val files: Map<String, Download>,
    val unmatchedCount: Int,
) {
    @Serializable
    data class Download(
        val uri: String,
        val currentNames: Set<String>,
        @Transient private val localFile: UniFile? = null,
    ) {
        constructor(file: UniFile, currentNames: Set<String>) : this(file.uri.toString(), currentNames, file)

        fun resolve(context: Context): UniFile? = localFile ?: UniFile.fromUri(context, uri.toUri())
    }

    val hasFiles: Boolean get() = files.isNotEmpty() || unmatchedCount > 0

    fun additionalCount(currentNames: Set<String>): Int {
        val overlapping = files.values.count { it.currentNames.any(currentNames::contains) }
        return LegacyChapterMatcher.additionalDownloadCount(files.size, unmatchedCount, currentNames.size, overlapping)
    }
}

/** Matches legacy chapter files in place. Used by the library, the reader, and the download queue. */
internal class LegacyMangaDownloadScanner(
    private val context: Context,
    private val provider: MangaDownloadProvider,
) {
    suspend fun scan(
        manga: Manga,
        source: MangaSource,
        chapters: List<Chapter>,
        folders: List<UniFile>,
        uniquelyOwnedFolders: Set<Uri>,
    ): LegacyMangaDownloads {
        val chapterByUrl = chapters.filter { it.url.isNotBlank() }.associateBy { it.url }
        if (folders.isEmpty()) return LegacyMangaDownloads(chapterByUrl.keys, emptyMap(), 0)

        val sourceHttp = source as? HttpSource
        val mangaUrls = webUrls(
            manga.url,
            sourceHttp?.let {
                runCatching {
                    it.getMangaUrl(
                        SManga.create().apply {
                            url = manga.url
                            title = manga.ogTitle
                        },
                    )
                }.getOrNull()
            },
        )
        val sourceHosts = mangaUrls.mapNotNullTo(mutableSetOf()) { it.host }
        runCatching { sourceHttp?.let { URI(it.baseUrl).host?.lowercase() } }
            .getOrNull()?.let(sourceHosts::add)

        val chapterUrlsByPath by lazy(LazyThreadSafetyMode.NONE) {
            chapterByUrl.values.flatMap { chapter ->
                val absoluteUrl = sourceHttp?.let {
                    runCatching {
                        it.getChapterUrl(
                            SChapter.create().apply {
                                url = chapter.url
                                name = chapter.name
                                scanlator = chapter.scanlator
                            },
                        )
                    }.getOrNull()
                }
                webUrls(chapter.url, absoluteUrl).map { it.path to (it.host to chapter.url) }
            }.groupBy({ it.first }, { it.second })
        }
        val matcher =
            LegacyChapterMatcher(chapterByUrl.mapValues { (_, chapter) -> provider.legacyChapterNames(chapter) })

        val identified = mutableMapOf<String, LegacyMangaDownloads.Download>()
        val unidentified = mutableSetOf<String>()
        val identifiedNames = mutableSetOf<String>()
        val scanContext = currentCoroutineContext()
        for (folder in folders) {
            scanContext.ensureActive()
            val mayUseTitle = folder.uri in uniquelyOwnedFolders
            val entries = folder.listFiles().orEmpty().mapNotNull { file ->
                scanContext.ensureActive()
                val name = file.name?.takeUnless(String::isBlank) ?: return@mapNotNull null
                if (name.endsWith(MangaDownloader.TMP_DIR_SUFFIX, ignoreCase = true) || name.startsWith('.')) {
                    return@mapNotNull null
                }
                if (!file.isDirectory && !name.endsWith(".cbz", ignoreCase = true)) return@mapNotNull null
                val stem = name.lowercase().removeSuffix(".cbz")
                val links = readPageMetadata(file, scanContext) ?: return@mapNotNull null
                stem to (file to links)
            }
            val singleDownload = entries.map { it.first }.distinct().size == 1
            for ((name, entry) in entries) {
                scanContext.ensureActive()
                val (file, links) = entry
                val galleryVerified = links.any { link -> mangaUrls.any { it.matches(link, mayUseTitle) } }
                if (!mayUseTitle && !galleryVerified) continue

                val verifiedChapters = links.flatMapTo(mutableSetOf()) { link ->
                    chapterUrlsByPath[link.path].orEmpty().mapNotNull { (host, chapterUrl) ->
                        chapterUrl.takeIf { mayUseTitle || host == null || host == link.host }
                    }
                }
                val hasOtherSourceLink = links.any { link -> link.host?.let(sourceHosts::contains) == true }
                if (chapterByUrl.isNotEmpty() && !galleryVerified && verifiedChapters.isEmpty() && hasOtherSourceLink) {
                    continue
                }

                val chapterUrl = matcher.match(name, verifiedChapters, singleDownload)
                val chapter = chapterUrl?.let(chapterByUrl::get)
                if (chapter == null) {
                    unidentified += name
                } else {
                    val currentNames = provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                        .mapTo(hashSetOf()) { it.removeSuffix(".cbz").lowercase() }
                    identified.putIfAbsent(chapter.url, LegacyMangaDownloads.Download(file, currentNames))
                    identifiedNames += name
                }
            }
        }
        return LegacyMangaDownloads(chapterByUrl.keys, identified, (unidentified - identifiedNames).size)
    }

    // A completed directory or archive has at least one image. Look at names
    // and small ComicInfo metadata only, without decoding any pages.
    private fun readPageMetadata(file: UniFile, scanContext: CoroutineContext): Set<WebUrl>? {
        return try {
            when {
                file.isDirectory -> {
                    val children = file.listFiles().orEmpty()
                    if (children.none { it.isFile && isImage(it.name) && it.length() > 0 }) return null
                    children.firstOrNull { it.name.equals("ComicInfo.xml", true) }
                        ?.openInputStream()?.use(::readComicInfo).orEmpty()
                }

                !file.isFile || file.length() == 0L -> null

                file.uri.scheme == "file" -> {
                    val path = file.uri.path ?: return null
                    ZipFile(File(path)).use { zip ->
                        if (zip.entries().asSequence().none { !it.isDirectory && isImage(it.name) && it.size > 0 }) {
                            return null
                        }
                        zip.getEntry("ComicInfo.xml")?.let { zip.getInputStream(it).use(::readComicInfo) }.orEmpty()
                    }
                }

                else -> readDocumentArchive(file, scanContext)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun readDocumentArchive(file: UniFile, scanContext: CoroutineContext): Set<WebUrl>? {
        return file.archiveReader(context).use { archive ->
            archive.useEntryStream { nextEntry, data ->
                var hasImage = false
                var links: Set<WebUrl>? = null
                while (!hasImage || links == null) {
                    scanContext.ensureActive()
                    val entry = nextEntry() ?: break
                    if (!entry.isFile) continue
                    if (isImage(entry.name)) hasImage = true
                    if (entry.name == "ComicInfo.xml" && links == null) links = readComicInfo(data)
                }
                if (hasImage) links.orEmpty() else null
            }
        }
    }

    private fun readComicInfo(input: InputStream): Set<WebUrl> {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val length = input.read(buffer)
            if (length <= 0) break
            if (bytes.size() + length > MAX_METADATA_BYTES) return emptySet()
            bytes.write(buffer, 0, length)
        }

        val parser = Xml.newPullParser()
        parser.setInput(bytes.toByteArray().inputStream(), Charsets.UTF_8.name())
        return buildSet {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Web") {
                    parser.nextText().split(WHITESPACE).mapNotNullTo(this, WebUrl::parse)
                }
                parser.next()
            }
        }
    }

    private fun webUrls(vararg urls: String?): Set<WebUrl> = urls.filterNotNull().mapNotNull(WebUrl::parse).toSet()

    private data class WebUrl(val host: String?, val path: String) {
        fun matches(other: WebUrl, allowDifferentHost: Boolean): Boolean =
            path == other.path && (allowDifferentHost || host == null || other.host == null || host == other.host)

        companion object {
            fun parse(url: String): WebUrl? {
                val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
                if (uri.scheme != null && !uri.scheme.equals("https", true) &&
                    !uri.scheme.equals("http", true)
                ) {
                    return null
                }
                val path = uri.rawPath?.trim('/')?.takeIf { it.isNotBlank() } ?: return null
                return WebUrl(uri.host?.lowercase(), path + uri.rawQuery?.let { "?$it" }.orEmpty())
            }
        }
    }

    private companion object {
        const val MAX_METADATA_BYTES = 512 * 1024
        val WHITESPACE = Regex("\\s+")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "jxl", "heic", "heif")

        fun isImage(name: String?) = name.orEmpty().substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }
}
