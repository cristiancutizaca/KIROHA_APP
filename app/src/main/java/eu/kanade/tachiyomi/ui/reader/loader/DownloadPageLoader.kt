package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.manga.toDomainChapter
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadManager
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadProvider
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.core.archive.archiveReader
import kiroha.core.AndroidKeyValueStore
import kiroha.core.DeviceRegistry
import kiroha.core.DownloadAvailability
import tachiyomi.data.kiroha.KirohaDatabaseRepository
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
internal class DownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: MangaSource,
    private val downloadManager: MangaDownloadManager,
    private val downloadProvider: MangaDownloadProvider,
    private val kirohaDatabaseRepository: KirohaDatabaseRepository,
    private val storageManager: StorageManager,
) : PageLoader() {

    private val context: Context by injectLazy()

    private var archivePageLoader: ArchivePageLoader? = null

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val dbChapter = chapter.chapter
        val chapterPath = indexedChapterPath() ?: downloadProvider.findChapterDir(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
            manga.url,
        )
        return if (chapterPath?.isFile == true) {
            getPagesFromArchive(chapterPath)
        } else {
            getPagesFromDirectory()
        }
    }

    private suspend fun indexedChapterPath(): UniFile? {
        val itemId = kiroha.core.KirohaIdentity.mangaChapter(source.id.toString(), manga.url, chapter.chapter.url)
        val deviceId = DeviceRegistry(AndroidKeyValueStore(context)).load()?.deviceId ?: return null
        val record = kirohaDatabaseRepository.downloadsForItem(itemId).firstOrNull {
            it.deviceId == deviceId &&
                it.state == DownloadAvailability.AVAILABLE_LOCAL && !it.localPath.isNullOrBlank()
        } ?: return null
        val root = storageManager.getDownloadsDirectory() ?: return null
        return record.localPath!!.split('/').fold(root) { current, part ->
            current.findFile(part) ?: return null
        }
    }

    override fun recycle() {
        super.recycle()
        archivePageLoader?.recycle()
    }

    private suspend fun getPagesFromArchive(file: UniFile): List<ReaderPage> {
        val loader = ArchivePageLoader(file.archiveReader(context)).also { archivePageLoader = it }
        return loader.getPages()
    }

    private fun getPagesFromDirectory(): List<ReaderPage> {
        val pages = downloadManager.buildPageList(source, manga, chapter.chapter.toDomainChapter()!!)
        return pages.map { page ->
            ReaderPage(page.index, page.url, page.imageUrl) {
                context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)!!
            }.apply {
                status = Page.State.READY
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        archivePageLoader?.loadPage(page)
    }
}
