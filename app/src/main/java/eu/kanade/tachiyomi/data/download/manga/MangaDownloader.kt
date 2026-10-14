package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import aniyomi.util.DataSaver
import aniyomi.util.DataSaver.Companion.getImage
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.entries.manga.model.getComicInfo
import eu.kanade.domain.items.chapter.model.toSChapter
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.manga.model.MangaDownload
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import eu.kanade.tachiyomi.util.storage.saveTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.Response
import okio.Throttler
import okio.buffer
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its queue contains the list of chapters to download. In order to download them, the downloader
 * subscription must be running and the list of chapters must be sent to them by [downloadsRelay].
 *
 * The queue manipulation must be done in one thread (currently the main thread) to avoid unexpected
 * behavior, but it's safe to read it from multiple threads.
 */
@Inject
@SingleIn(AppScope::class)
class MangaDownloader(
    private val context: Context,
    private val provider: MangaDownloadProvider,
    private val cache: MangaDownloadCache,
    private val sourceManager: MangaSourceManager,
    private val chapterCache: ChapterCache,
    private val downloadPreferences: DownloadPreferences,
    private val xml: XML,
    private val getCategories: GetMangaCategories,
    private val getMangaTracks: GetMangaTracks,
    private val store: MangaDownloadStore,
    private val notifier: MangaDownloadNotifier,
    // SY -->
    private val sourcePreferences: SourcePreferences,
    // SY <--
) {
    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<MangaDownload>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The throttler used to control the download speed.
     */
    private val throttler = Throttler()
    private var downloaderJob: Job? = null

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = store.isPaused()

    // The worker must wait for on-disk queue restoration before deciding it is empty.
    private val restorationJob: Job = scope.launch {
        // The installed extension registry is populated asynchronously.
        // Restoring before it is initialized drops every saved task whose
        // source is not yet visible, making the queue look empty on relaunch.
        sourceManager.isInitialized.first { it }
        val restored = store.restore()
        restored.forEach { it.status = MangaDownload.State.QUEUE }
        // These items are already durable; do not rewrite 1,000 preference entries
        // merely because the UI process was recreated.
        _queueState.update { current ->
            val present = current.mapTo(hashSetOf()) { it.chapter.id }
            restored.filter { present.add(it.chapter.id) } + current
        }
    }

    suspend fun awaitRestoration() {
        restorationJob.join()
    }

    /** A recovered paused queue must not be restarted just because the process relaunched. */
    fun hasAutoResumeWork(): Boolean = !store.isPaused() && queueState.value.isNotEmpty()

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != MangaDownload.State.DOWNLOADED }
        pending.forEach { if (it.status != MangaDownload.State.QUEUE) it.status = MangaDownload.State.QUEUE }

        isPaused = false
        store.setPaused(false)

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == MangaDownload.State.DOWNLOADING }
            .forEach { it.status = MangaDownload.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        isPaused = false

        MangaDownloadJob.stop(context)
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == MangaDownload.State.DOWNLOADING }
            .forEach { it.status = MangaDownload.State.QUEUE }
        isPaused = true
        store.setPaused(true)
    }

    /**
     * Removes everything from the queue.
     */
    fun clearQueue() {
        cancelDownloaderJob()

        internalClearQueue()
        notifier.dismissProgress()
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun launchDownloaderJob() {
        if (isRunning) return

        downloaderJob = scope.launchIO {
            val activeDownloadsFlow = queueState.transformLatest { queue ->
                while (true) {
                    val activeDownloads = queue.asSequence()
                        .filter {
                            it.status.value <= MangaDownload.State.DOWNLOADING.value
                        } // Ignore completed downloads, leave them in the queue
                        .groupBy { it.source }
                        .toList().take(5)
                        .flatMap { (_, downloads) -> downloads.take(2) }
                        // The previous per-source limit multiplied by five sources, causing
                        // up to 25 simultaneous chapter jobs (each fetching multiple pages).
                        // Enforce one global cap to reduce CPU, network and thermal load.
                        .take(minOf(downloadPreferences.numberOfDownloads.get().coerceIn(1, 5), thermalChapterLimit(), MAX_CONCURRENT_CHAPTERS))
                    emit(activeDownloads)

                    if (activeDownloads.isEmpty()) break
                    // Suspend until a download enters the ERROR state
                    val activeDownloadsErroredFlow =
                        combine(activeDownloads.map(MangaDownload::statusFlow)) { states ->
                            states.contains(MangaDownload.State.ERROR)
                        }.filter { it }
                    activeDownloadsErroredFlow.first()
                }
            }.distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val downloadJobs = mutableMapOf<MangaDownload, Job>()

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val downloadJobsToStop = downloadJobs.filter { it.key !in activeDownloads }
                    downloadJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        downloadJobs.remove(download)
                    }

                    val downloadsToStart = activeDownloads.filter { it !in downloadJobs }
                    downloadsToStart.forEach { download ->
                        downloadJobs[download] = launchDownloadJob(download)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchDownloadJob(download: MangaDownload) = launchIO {
        try {
            downloadChapter(download)

            // Remove successful download from queue
            if (download.status == MangaDownload.State.DOWNLOADED) {
                removeFromQueue(download)
            }
            if (areAllDownloadsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            notifier.onError(e.message)
            stop()
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        downloaderJob?.cancel()
        downloaderJob = null
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
        if (chapters.isEmpty()) return

        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        // Reading queueState and scanning the whole queue for every chapter is
        // quadratic when selecting a long series. Reject queued IDs first so
        // we also avoid needless SAF reads for already-queued chapters.
        val queuedChapterIds = queueState.value.mapTo(hashSetOf()) { it.chapter.id }
        val chaptersToQueue = chapters.asSequence()
            .filter { it.id !in queuedChapterIds }
            // Check disk only for chapters not already in the queue.
            .filter { provider.findChapterDir(it.name, it.scanlator, it.url, manga.title, source, manga.url) == null }
            // Add chapters to queue from the start.
            .sortedByDescending { it.sourceOrder }
            .map { MangaDownload(source, manga, it) }
            .toList()

        if (chaptersToQueue.isNotEmpty()) {
            addAllToQueue(chaptersToQueue)

            // Start downloader if needed
            if (autoStart && !isRunning && !isPaused) {
                val queuedDownloads = queueState.value.count { it: MangaDownload -> it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size }
                    ?: 0
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    notifier.onWarning(
                        context.stringResource(AYMR.strings.download_queue_size_warning),
                        WARNING_NOTIF_TIMEOUT_MS,
                        NotificationHandler.openUrl(context, MangaLibraryUpdateNotifier.HELP_WARNING_URL),
                    )
                }
                MangaDownloadJob.start(context)
            }
        }
    }

    /**
     * Downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     */
    private suspend fun downloadChapter(download: MangaDownload) {
        val mangaDir = provider.getMangaDir(download.manga.title, download.source, download.manga.url)

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = MangaDownload.State.ERROR
            notifier.onError(
                context.stringResource(AYMR.strings.download_insufficient_space),
                download.chapter.name,
                download.manga.title,
                download.manga.id,
            )
            return
        }

        val chapterDirname = provider.getChapterDirName(
            download.chapter.name,
            download.chapter.scanlator,
            download.chapter.url,
        )
        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)!!

        try {
            // If the page list already exists, start from the file
            val pageList = download.pages ?: run {
                // Otherwise, pull page list from network and add them to download object
                val pages = download.source.getPageList(download.chapter.toSChapter())

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }
                // Don't trust index from source
                val reIndexedPages = pages.mapIndexed { index, page ->
                    Page(
                        index,
                        page.url,
                        page.imageUrl,
                        page.uri,
                    )
                }
                download.pages = reIndexedPages
                reIndexedPages
            }

            val dataSaver = if (sourcePreferences.dataSaverDownloader.get()) {
                DataSaver(download.source, sourcePreferences)
            } else {
                DataSaver.NoOp
            }

            // Preserve partial .tmp page files across process death. downloadImage()
            // already requests the existing byte offset and overwrites from zero
            // if the server does not support HTTP 206 range responses.
            download.status = MangaDownload.State.DOWNLOADING

            // Snapshot existing completed pages ONCE per chapter. The previous
            // getOrDownloadImage() called listFiles() for every page, resulting in
            // quadratic SAF directory scans on long chapters.
            val existingImages = tmpDir.listFiles().orEmpty()
                .asSequence()
                .filter { it.isFile && it.name?.endsWith(".tmp") != true }
                .groupBy { file -> file.name.orEmpty().substringBefore('.').substringBefore("__") }

            // Start downloading images, consider we can have downloaded images already
            // A few chapters can fetch many pages concurrently; throttle per chapter
            // when the OS reports a hot device instead of multiplying socket load.
            var lastNotificationAt = 0L
            pageList.asFlow()
                .flatMapMerge(concurrency = if (thermalChapterLimit() <= 2) 1 else 2) { page ->
                    flow {
                        // Keep completed images offline: do not call the extension
                        // for image URLs that already exist in the restored temp folder.
                        val digits = pageList.size.toString().length.coerceAtLeast(3)
                        val prefix = "%0${digits}d".format(Locale.ENGLISH, page.number)
                        val alreadySaved = existingImages[prefix]?.any {
                            isDownloadedPageImage(it.name.orEmpty(), prefix)
                        } == true
                        if (!alreadySaved && page.imageUrl.isNullOrEmpty()) {
                            page.status = Page.State.LOAD_PAGE
                            try {
                                page.imageUrl = download.source.getImageUrl(page)
                            } catch (e: Throwable) {
                                page.status = Page.State.ERROR
                            }
                        }

                        withIOContext { getOrDownloadImage(page, download, tmpDir, dataSaver, existingImages) }
                        emit(page)
                    }.flowOn(Dispatchers.IO)
                }
                .collect {
                    // Avoid hundreds of notification updates and full page-status
                    // recounts per chapter. Final state is always sent below.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotificationAt >= PROGRESS_NOTIFICATION_INTERVAL_MS) {
                        lastNotificationAt = now
                        notifier.onProgressChange(download)
                    }
                }
            notifier.onProgressChange(download)

            // Do after download completes
            if (!isDownloadSuccessful(download, tmpDir)) {
                download.status = MangaDownload.State.ERROR
                return
            }

            createComicInfoFile(
                tmpDir,
                download.manga,
                download.chapter,
                download.source,
            )

            // Only rename the directory if it's downloaded
            if (downloadPreferences.saveChaptersAsCBZ.get()) {
                archiveChapter(mangaDir, chapterDirname, tmpDir)
            } else {
                tmpDir.renameTo(chapterDirname)
            }
            cache.addChapter(chapterDirname, mangaDir, download.manga)

            DiskUtil.createNoMediaFile(tmpDir, context)

            download.status = MangaDownload.State.DOWNLOADED
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // If the page list threw, it will resume here
            logcat(LogPriority.ERROR, error)
            download.status = MangaDownload.State.ERROR
            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Gets the image from the filesystem if it exists or downloads it otherwise.
     *
     * @param page the page to download.
     * @param download the download of the page.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadImage(
        page: Page,
        download: MangaDownload,
        tmpDir: UniFile,
        dataSaver: DataSaver,
        existingImages: Map<String, List<UniFile>>,
    ) {
        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)

        // An indexed lookup avoids scanning the SAF folder again for each page.
        val imageFile = existingImages[filename]?.firstOrNull {
            isDownloadedPageImage(it.name.orEmpty(), filename)
        }

        // A saved page is valid without a freshly fetched image URL. Only pages
        // that are still missing require the source to provide one.
        if (imageFile == null && page.imageUrl.isNullOrEmpty()) return

        try {
            // If the image is already downloaded, do nothing. Otherwise download from network
            val file = when {
                imageFile != null -> imageFile

                chapterCache.isImageInCache(page.imageUrl!!) -> copyImageFromCache(
                    chapterCache.getImageFile(page.imageUrl!!),
                    tmpDir,
                    filename,
                )

                else -> downloadImage(page, download.source, tmpDir, filename, dataSaver)
            }

            // When the page is ready, set page path, progress (just in case) and status
            splitTallImageIfNeeded(page, tmpDir)
            page.uri = file.uri
            page.progress = 100
            page.status = Page.State.READY
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Mark this page as error and allow to download the remaining
            page.progress = 0
            page.status = Page.State.ERROR
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Downloads the image from network to a file in tmpDir.
     *
     * @param page the page to download.
     * @param source the source of the page.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private suspend fun downloadImage(
        page: Page,
        source: HttpSource,
        tmpDir: UniFile,
        filename: String,
        dataSaver: DataSaver,
    ): UniFile {
        page.status = Page.State.DOWNLOAD_IMAGE
        page.progress = 0
        return flow {
            val file = tmpDir.findFile("$filename.tmp")
                ?: tmpDir.createFile("$filename.tmp")!!

            try {
                source.getImage(page, dataSaver, file.length()).use {
                    throttler.apply {
                        bytesPerSecond(downloadPreferences.downloadSpeedLimit.get().toLong() * 1024)
                    }
                    val throttledSource = throttler.source(it.body.source()).buffer()
                    throttledSource.saveTo(
                        // If the server supports partial downloads (HTTP 206),
                        // append to the existing file.
                        // Otherwise, start from scratch and overwrite the file.
                        stream = file.openOutputStream(it.code == 206),
                    )
                    val extension = getImageExtension(it, file)
                    file.renameTo("$filename.$extension")
                }
            } catch (e: HttpException) {
                if (e.code == 416) {
                    file.delete()
                }
                throw e
            }
            emit(file)
        }
            // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
            .retryWhen { _, attempt ->
                if (attempt < 3) {
                    delay((2L shl attempt.toInt()).seconds)
                    true
                } else {
                    false
                }
            }
            .flowOn(Dispatchers.IO)
            .first()
    }

    /**
     * Copies the image from cache to file in tmpDir.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private fun copyImageFromCache(cacheFile: File, tmpDir: UniFile, filename: String): UniFile {
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: return tmpFile
        tmpFile.renameTo("$filename.${extension.extension}")
        cacheFile.delete()
        return tmpFile
    }

    /**
     * Returns the extension of the downloaded image from the network response, or if it's null,
     * analyze the file. If everything fails, assume it's a jpg.
     *
     * @param response the network response of the image.
     * @param file the file where the image is already downloaded.
     */
    private fun getImageExtension(response: Response, file: UniFile): String {
        val mime = response.body.contentType()?.run { if (type == "image") "image/$subtype" else null }
        return ImageUtil.getExtensionFromMimeType(mime) { file.openInputStream() }
    }

    private fun splitTallImageIfNeeded(page: Page, tmpDir: UniFile) {
        if (!downloadPreferences.splitTallImages.get()) return

        try {
            val filenamePrefix = "%03d".format(Locale.ENGLISH, page.number)
            val imageFile = tmpDir.listFiles()?.firstOrNull { it.name.orEmpty().startsWith(filenamePrefix) }
                ?: error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))

            // If the original page was previously split, then skip
            if (imageFile.name.orEmpty().startsWith("${filenamePrefix}__")) return

            ImageUtil.splitTallImage(
                tmpDir,
                imageFile,
                filenamePrefix,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    private fun isDownloadSuccessful(
        download: MangaDownload,
        tmpDir: UniFile,
    ): Boolean {
        // Page list hasn't been initialized
        val downloadPageCount = download.pages?.size ?: return false
        // Ensure that all pages have been downloaded
        if (download.downloadedImages != downloadPageCount) {
            return false
        }
        // Ensure that the chapter folder has all the pages
        val downloadedImagesCount = tmpDir.listFiles().orEmpty().count {
            val fileName = it.name.orEmpty()
            when {
                fileName in listOf(COMIC_INFO_FILE, NOMEDIA_FILE) -> false

                fileName.endsWith(".tmp") -> false

                // Only count the first split page and not the others
                fileName.contains("__") && !fileName.endsWith("__001.jpg") -> false

                else -> true
            }
        }
        return downloadedImagesCount == downloadPageCount
    }

    /**
     * Checks if the file name matches a downloaded page image.
     *
     * @param fileName Name of the file to check
     * @param pagePrefix Expected page prefix (e.g., "001")
     */
    private fun isDownloadedPageImage(fileName: String, pagePrefix: String): Boolean =
        !fileName.endsWith(".tmp") && (
            fileName.startsWith("$pagePrefix.") ||
                fileName.startsWith("${pagePrefix}__001.")
            )

    /**
     * Archive the chapter pages as a CBZ.
     */
    private fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
    ) {
        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")!!
        ZipWriter(context, zip).use { writer ->
            tmpDir.listFiles()?.forEach { file ->
                writer.write(file)
            }
        }
        zip.renameTo("$dirname.cbz")
        tmpDir.delete()
    }

    /**
     * Creates a ComicInfo.xml file inside the given directory.
     */
    private suspend fun createComicInfoFile(
        dir: UniFile,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ) {
        val categories = getCategories.await(manga.id).map { it.name.trim() }.takeUnless { it.isEmpty() }
        val urls = getMangaTracks.await(manga.id)
            .mapNotNull { track ->
                track.remoteUrl.takeUnless { url -> url.isBlank() }?.trim()
            }
            .plus(source.getChapterUrl(chapter.toSChapter()).trim())
            .distinct()

        val comicInfo = getComicInfo(
            manga,
            chapter,
            urls,
            categories,
            source.name,
        )

        // Remove the old file
        dir.findFile(COMIC_INFO_FILE)?.delete()
        dir.createFile(COMIC_INFO_FILE)!!.openOutputStream().use {
            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
            it.write(comicInfoString.toByteArray())
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= MangaDownload.State.DOWNLOADING.value }
    }

    private fun addAllToQueue(downloads: List<MangaDownload>) {
        if (downloads.isEmpty()) return
        val existing = queueState.value.mapTo(hashSetOf()) { it.chapter.id }
        val newDownloads = downloads.filter { existing.add(it.chapter.id) }
        if (newDownloads.isEmpty()) return
        newDownloads.forEach { it.status = MangaDownload.State.QUEUE }
        // Persist first. Do not perform disk writes inside StateFlow.update's CAS retry.
        store.addAll(newDownloads)
        _queueState.update { current ->
            val present = current.mapTo(hashSetOf()) { it.chapter.id }
            current + newDownloads.filter { present.add(it.chapter.id) }
        }
    }

    private fun removeFromQueue(download: MangaDownload) {
        store.remove(download)
        if (download.status == MangaDownload.State.DOWNLOADING ||
            download.status == MangaDownload.State.QUEUE
        ) download.status = MangaDownload.State.NOT_DOWNLOADED
        _queueState.update { current -> current - download }
    }

    private inline fun removeFromQueueIf(predicate: (MangaDownload) -> Boolean) {
        val downloads = queueState.value.filter(predicate)
        if (downloads.isEmpty()) return
        store.removeAll(downloads)
        downloads.forEach { download ->
            if (download.status == MangaDownload.State.DOWNLOADING ||
                download.status == MangaDownload.State.QUEUE
            ) download.status = MangaDownload.State.NOT_DOWNLOADED
        }
        val removed = downloads.toSet()
        _queueState.update { current -> current.filterNot { it in removed } }
    }

    fun removeFromQueue(chapters: List<Chapter>) {
        val chapterIds = chapters.map { it.id }
        removeFromQueueIf { it.chapter.id in chapterIds }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        store.clear()
        val previous = _queueState.value
        previous.forEach { download ->
            if (download.status == MangaDownload.State.DOWNLOADING ||
                download.status == MangaDownload.State.QUEUE
            ) download.status = MangaDownload.State.NOT_DOWNLOADED
        }
        _queueState.value = emptyList()
    }

    fun updateQueue(downloads: List<MangaDownload>) {
        val wasRunning = isRunning

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        pause()
        downloads.forEach { it.status = MangaDownload.State.QUEUE }
        // A two-step clear + add can permanently erase queued work on process death.
        store.replaceAll(downloads)
        _queueState.value = downloads.distinctBy { it.chapter.id }

        if (wasRunning) {
            start()
        }
    }

    private fun thermalChapterLimit(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 5
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return 5
        return when {
            powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> 1
            powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 2
            else -> 5
        }
    }

    companion object {
        private const val MAX_CONCURRENT_CHAPTERS = 2
        private const val PROGRESS_NOTIFICATION_INTERVAL_MS = 1_000L
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024
