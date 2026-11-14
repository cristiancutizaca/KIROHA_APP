package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.util.size
import kiroha.core.KirohaDownloadPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Cache where we dump the downloads directory from the filesystem. This class is needed because
 * directory checking is expensive and it slows down the app. The cache is invalidated by the time
 * defined in [renewInterval] as we don't have any control over the filesystem and the user can
 * delete the folders at any time without the app noticing.
 */
@Inject
@SingleIn(AppScope::class)
class MangaDownloadCache(
    private val context: Context,
    private val provider: MangaDownloadProvider,
    private val sourceManager: MangaSourceManager,
    private val extensionManager: MangaExtensionManager,
    private val storageManager: StorageManager,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _changes: Channel<Unit> = Channel(Channel.CONFLATED)
    val changes = _changes.receiveAsFlow()
        .onStart { emit(Unit) }
        .flowOn(Dispatchers.IO)
        .shareIn(scope, SharingStarted.Lazily, 1)

    /**
     * The interval after which this cache should be invalidated. 1 hour shouldn't cause major
     * issues, as the cache is only used for UI feedback.
     */
    private val renewInterval = 1.hours.inWholeMilliseconds

    /**
     * The last time the cache was refreshed.
     */
    @Volatile private var lastRenew = 0L

    @Volatile private var nextRenewAttempt = 0L
    private var renewalJob: Job? = null
    private val diskCacheReady = MutableStateFlow(false)

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing
        .debounce(1.seconds) // Don't notify if it finishes quickly enough
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val diskCacheFile: File
        get() = File(context.cacheDir, "dl_index_cache_v4")

    private val rootDownloadsDirMutex = Mutex()

    @Volatile private var rootDownloadsDir = RootDirectory(null)

    // Never guess legacy ownership from the visible title. Verify only individual
    // chapters on Dispatchers.IO, serially, to avoid hammering the user's SD card.
    private data class LegacyChapterKey(
        val chapterName: String,
        val chapterScanlator: String?,
        val chapterUrl: String,
        val mangaTitle: String,
        val mangaUrl: String,
        val sourceId: Long,
    )

    private data class LegacyMangaKey(val mangaUrl: String, val mangaTitle: String, val sourceId: Long)

    private val legacyResults = ConcurrentHashMap<LegacyChapterKey, Boolean>()

    // A search result may be checked before its manga/chapters are persisted.
    // Retry negative matches later so opening that gallery can recover it.
    private val legacyNegativeChecks = ConcurrentHashMap<LegacyChapterKey, Long>()
    private data class LegacyChapters(val checkedAt: Long, val chapters: List<Chapter>)
    private val legacyChapters = ConcurrentHashMap<LegacyMangaKey, LegacyChapters>()
    private val requestedLegacyManga = ConcurrentHashMap.newKeySet<LegacyMangaKey>()
    private var legacyMangaScanJob: Job? = null

    // O(1) download counters for large libraries. Counting all chapter checks
    // for every library card was quadratic as the positive cache grew.
    private val legacyCounts = ConcurrentHashMap<LegacyMangaKey, AtomicInteger>()
    private val legacyInFlight = ConcurrentHashMap.newKeySet<LegacyChapterKey>()
    private val deferredLegacyChecks = ConcurrentLinkedQueue<LegacyChapterKey>()
    private val deferredLegacyCount = AtomicInteger(0)
    private val pendingLegacyChecks = AtomicInteger(0)
    private val legacyGeneration = AtomicLong(0)
    private val legacyProbeLimit = Semaphore(1)
    private var legacyNotificationJob: Job? = null

    // Old Aniyomi folders have no URL suffix. Keep the strict unique-owner
    // rule for library entries, but also recover single-chapter galleries that
    // the user opens directly from the source without favoriting 600+ items.
    private data class LegacyLibraryKey(val sourceId: Long, val title: String)
    private val legacyLibraryOwnerMutex = Mutex()

    // Do not discard duplicate titles: ambiguity must block legacy recovery.
    @Volatile private var legacyLibraryOwners: Map<LegacyLibraryKey, Set<String>>? = null

    private suspend fun chapterCountForLegacyRecovery(key: LegacyChapterKey): Int? {
        val favoriteOwners = legacyLibraryOwners ?: legacyLibraryOwnerMutex.withLock {
            legacyLibraryOwners ?: mangaRepository.getMangaFavorites()
                .groupBy { LegacyLibraryKey(it.source, it.title) }
                .mapValues { (_, manga) -> manga.mapTo(mutableSetOf()) { it.url } }
                .also { legacyLibraryOwners = it }
        }
        val owners = favoriteOwners[LegacyLibraryKey(key.sourceId, key.mangaTitle)].orEmpty()
        val manga = mangaRepository.getMangaByUrlAndSourceId(key.mangaUrl, key.sourceId) ?: return null
        if (manga.title != key.mangaTitle) return null
        val mangaKey = LegacyMangaKey(key.mangaUrl, key.mangaTitle, key.sourceId)
        val chapters = getLegacyChapters(mangaKey, manga.id)
        val canRecover = KirohaDownloadPaths.permitsLegacyTitleRecovery(
            favoriteOwnerUrls = owners,
            mangaUrl = key.mangaUrl,
            isFavorite = manga.favorite,
            chapterCount = chapters.size,
            matchingChapterCount = chapters.count { it.url == key.chapterUrl },
        )
        return chapters.size.takeIf { canRecover }
    }

    private suspend fun getLegacyChapters(key: LegacyMangaKey, mangaId: Long): List<Chapter> {
        val now = System.currentTimeMillis()
        legacyChapters[key]?.takeIf { now - it.checkedAt < LEGACY_NEGATIVE_RECHECK_MS }?.let {
            return it.chapters
        }
        return chapterRepository.getChapterByMangaId(mangaId).also {
            if (it.isNotEmpty()) legacyChapters[key] = LegacyChapters(now, it)
        }
    }

    private companion object {
        const val MAX_PENDING_LEGACY_CHECKS = 24
        const val MAX_DEFERRED_LEGACY_CHECKS = 4096
        const val LEGACY_NEGATIVE_RECHECK_MS = 60_000L
    }

    init {
        // Attempt to read cache file
        scope.launchIO {
            try {
                rootDownloadsDirMutex.withLock {
                    rootDownloadsDir = RootDirectory(storageManager.getDownloadsDirectory())
                    if (diskCacheFile.exists()) {
                        val diskCache = AtomicFile(diskCacheFile).openRead().use {
                            ProtoBuf.decodeFromByteArray<RootDirectory>(it.readBytes())
                        }
                        if (diskCache.dir?.uri == rootDownloadsDir.dir?.uri && diskCache.dir != null) {
                            rootDownloadsDir = diskCache
                            lastRenew = diskCacheFile.lastModified()
                            _changes.trySend(Unit)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logcat(LogPriority.ERROR, error) { "Failed to initialize from disk cache" }
                AtomicFile(diskCacheFile).delete()
            } finally {
                diskCacheReady.value = true
                renewCache()
            }
        }

        storageManager.changes
            .onEach {
                rootDownloadsDirMutex.withLock {
                    val root = storageManager.getDownloadsDirectory()
                    if (rootDownloadsDir.dir?.uri != root?.uri) rootDownloadsDir = RootDirectory(root)
                }
                invalidateCache()
            }
            .launchIn(scope)
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     * @param skipCache whether to skip the directory cache and check in the filesystem.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
        skipCache: Boolean,
        mangaUrl: String? = null,
    ): Boolean {
        if (mangaUrl.isNullOrBlank()) return false
        if (skipCache) {
            val source = sourceManager.getOrStub(sourceId)
            return provider.findChapterDir(
                chapterName,
                chapterScanlator,
                chapterUrl,
                mangaTitle,
                source,
                mangaUrl,
            ) != null
        }

        renewCache()
        val mangaDir = rootDownloadsDir.sourceDirs[sourceId]
            ?.mangaDirs?.get(provider.getMangaDirName(mangaTitle, mangaUrl, sourceId))
        if (mangaDir != null && provider.getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl)
                .any { it in mangaDir.chapterDirs }
        ) {
            return true
        }

        // After a source's legacy folders have been indexed, most search results
        // can be rejected in memory. Do not enqueue thousands of no-op SAF and
        // database probes for titles that were never downloaded.
        if (provider.isLegacyMangaTitleKnownAbsent(mangaTitle, sourceId)) return false

        val key = LegacyChapterKey(chapterName, chapterScanlator, chapterUrl, mangaTitle, mangaUrl, sourceId)
        legacyResults[key]?.let { cached ->
            if (cached) return true
            val lastChecked = legacyNegativeChecks[key] ?: 0L
            if (System.currentTimeMillis() - lastChecked < LEGACY_NEGATIVE_RECHECK_MS) return false
            legacyResults.remove(key, false)
            legacyNegativeChecks.remove(key)
        }

        if (legacyInFlight.add(key)) {
            // A library can present thousands of chapters at once. Avoid
            // scheduling thousands of coroutines waiting on one SD card.
            if (pendingLegacyChecks.incrementAndGet() > MAX_PENDING_LEGACY_CHECKS) {
                pendingLegacyChecks.decrementAndGet()
                // Don't silently lose all chapters beyond the first 24 visible.
                // Keep a bounded, background-only queue and drain it as probes finish.
                if (deferredLegacyCount.incrementAndGet() <= MAX_DEFERRED_LEGACY_CHECKS) {
                    deferredLegacyChecks.offer(key)
                } else {
                    deferredLegacyCount.decrementAndGet()
                    legacyInFlight.remove(key)
                }
                return false
            }
            val generation = legacyGeneration.get()
            scope.launchIO {
                try {
                    // Extension sources must be initialized before trying to validate
                    // ComicInfo chapter URLs. A stub must not cache a false result.
                    extensionManager.isInitialized.first { it }
                    sourceManager.isInitialized.first { it }
                    if (generation != legacyGeneration.get()) return@launchIO
                    val found = legacyProbeLimit.withPermit {
                        val source = sourceManager.getOrStub(sourceId)
                        findLegacyChapter(source, key) != null
                    }
                    if (generation != legacyGeneration.get()) return@launchIO
                    val previous = legacyResults.put(key, found)
                    if (found) {
                        legacyNegativeChecks.remove(key)
                        if (previous != true) {
                            val mangaKey = LegacyMangaKey(key.mangaUrl, key.mangaTitle, key.sourceId)
                            legacyCounts.computeIfAbsent(mangaKey) { AtomicInteger(0) }.incrementAndGet()
                        }
                        // Throttle instead of debounce: a long legacy scan must
                        // update the UI periodically, not only after its last hit.
                        if (legacyNotificationJob?.isActive != true) {
                            legacyNotificationJob = scope.launchIO {
                                delay(500L)
                                _changes.send(Unit)
                            }
                        }
                    } else {
                        legacyNegativeChecks[key] = System.currentTimeMillis()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logcat(LogPriority.WARN, error) { "Legacy manga chapter validation failed" }
                    if (generation == legacyGeneration.get()) {
                        legacyResults[key] = false
                        legacyNegativeChecks[key] = System.currentTimeMillis()
                    }
                } finally {
                    legacyInFlight.remove(key)
                    pendingLegacyChecks.decrementAndGet()
                    scheduleNextDeferredLegacyCheck()
                }
            }
        }
        return false
    }

    /** Resolve a chapter opened before the background legacy checks have reached it. */
    suspend fun resolveChapterFile(manga: Manga, chapter: Chapter, source: MangaSource): UniFile? {
        val current = provider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
            manga.url,
            checkLegacy = false,
        )
        if (current != null || provider.isLegacyMangaTitleKnownAbsent(manga.title, source.id)) return current

        return legacyProbeLimit.withPermit {
            findLegacyChapter(
                source,
                LegacyChapterKey(chapter.name, chapter.scanlator, chapter.url, manga.title, manga.url, source.id),
            )
        }
    }

    private suspend fun findLegacyChapter(source: MangaSource, key: LegacyChapterKey): UniFile? {
        if (!provider.hasLegacyMangaTitleFolder(key.mangaTitle, source)) return null
        val verified = provider.findVerifiedLegacyChapterDir(
            key.chapterName,
            key.chapterScanlator,
            key.chapterUrl,
            key.mangaTitle,
            source,
            key.mangaUrl,
        )
        if (verified != null) return verified

        val count = chapterCountForLegacyRecovery(key) ?: return null
        return provider.findUniqueLegacyChapterDir(
            chapterName = key.chapterName,
            chapterScanlator = key.chapterScanlator,
            chapterUrl = key.chapterUrl,
            mangaTitle = key.mangaTitle,
            source = source,
            mangaUrl = key.mangaUrl,
            allowUnnamedSingleChapter = count == 1,
        )
    }

    private fun scheduleNextDeferredLegacyCheck() {
        val generation = legacyGeneration.get()
        val next = deferredLegacyChecks.poll() ?: return
        deferredLegacyCount.decrementAndGet()
        legacyInFlight.remove(next)
        scope.launchIO {
            // Avoid saturating SAF/microSD when a large library first opens.
            delay(125L)
            if (generation == legacyGeneration.get() && legacyResults[next] == null) {
                isChapterDownloaded(
                    chapterName = next.chapterName,
                    chapterScanlator = next.chapterScanlator,
                    chapterUrl = next.chapterUrl,
                    mangaTitle = next.mangaTitle,
                    sourceId = next.sourceId,
                    skipCache = false,
                    mangaUrl = next.mangaUrl,
                )
            }
        }
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getTotalDownloadCount(): Int {
        renewCache()

        val current = rootDownloadsDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.mangaDirs.values.sumOf { mangaDir ->
                mangaDir.chapterDirs.size
            }
        }
        // The namespaced index does not contain recovered legacy downloads.
        return current + legacyCounts.values.sumOf { it.get() }
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        renewCache()
        val current = rootDownloadsDir.sourceDirs[manga.source]
            ?.mangaDirs?.get(provider.getMangaDirName(manga.title, manga.url, manga.source))
            ?.chapterDirs?.size ?: 0

        val key = LegacyMangaKey(manga.url, manga.title, manga.source)
        return current + (legacyCounts[key]?.get() ?: 0)
    }

    @Synchronized
    fun checkLegacyDownloads(mangas: List<Manga>) {
        if (legacyMangaScanJob?.isActive == true) return
        val generation = legacyGeneration.get()
        legacyMangaScanJob = scope.launchIO {
            try {
                extensionManager.isInitialized.first { it }
                sourceManager.isInitialized.first { it }
                if (!provider.hasAnyLegacySourceFolder()) return@launchIO
                for (manga in mangas) {
                    ensureActive()
                    if (generation != legacyGeneration.get()) break
                    if (manga.url.isBlank() ||
                        provider.isLegacyMangaTitleKnownAbsent(manga.title, manga.source)
                    ) {
                        continue
                    }
                    val key = LegacyMangaKey(manga.url, manga.title, manga.source)
                    if (!requestedLegacyManga.add(key)) continue
                    val source = sourceManager.getOrStub(manga.source)
                    if (!provider.hasLegacyMangaTitleFolder(manga.title, source)) continue
                    val chapters = getLegacyChapters(key, manga.id)
                    for (chapter in chapters) {
                        ensureActive()
                        while (deferredLegacyCount.get() >= MAX_DEFERRED_LEGACY_CHECKS) {
                            delay(250L)
                        }
                        isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            manga.title,
                            manga.source,
                            skipCache = false,
                            mangaUrl = manga.url,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Could not check legacy downloads for library entries" }
            }
        }
    }

    /**
     * Returns the total size of downloaded chapters.
     */
    fun getTotalDownloadSize(): Long {
        renewCache()

        return rootDownloadsDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.dir?.size() ?: 0L
        }
    }

    /**
     * Returns the total size of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadSize(manga: Manga): Long {
        renewCache()

        return rootDownloadsDir.sourceDirs[manga.source]?.mangaDirs?.get(
            provider.getMangaDirName(manga.title, manga.url, manga.source),
        )?.dir?.size() ?: 0
    }

    /**
     * Adds a chapter that has just been download to this cache.
     *
     * @param chapterDirName the downloaded chapter's directory name.
     * @param mangaUniFile the directory of the manga.
     * @param manga the manga of the chapter.
     */
    suspend fun addChapter(chapterDirName: String, mangaUniFile: UniFile, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            // Retrieve the cached source directory or cache a new one
            var sourceDir = rootDownloadsDir.sourceDirs[manga.source]
            if (sourceDir == null) {
                val source = sourceManager.get(manga.source) ?: return
                val sourceUniFile = provider.findSourceDir(source) ?: return
                sourceDir = SourceDirectory(sourceUniFile)
                rootDownloadsDir.sourceDirs += manga.source to sourceDir
            }

            // Retrieve the cached manga directory or cache a new one
            val mangaDirName = provider.getMangaDirName(manga.title, manga.url, manga.source)
            var mangaDir = sourceDir.mangaDirs[mangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += mangaDirName to mangaDir
            }

            // Save the chapter directory
            mangaDir.chapterDirs += chapterDirName
        }

        notifyChanges()
    }

    /**
     * Removes a chapter that has been deleted from this cache.
     *
     * @param chapter the chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapter(chapter: Chapter, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title, manga.url, manga.source)] ?: return
            provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).forEach {
                if (it in mangaDir.chapterDirs) {
                    mangaDir.chapterDirs -= it
                }
            }
        }

        notifyChanges()
    }

    /**
     * Removes a list of chapters that have been deleted from this cache.
     *
     * @param chapters the list of chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapters(chapters: List<Chapter>, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title, manga.url, manga.source)] ?: return
            chapters.forEach { chapter ->
                provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).forEach {
                    if (it in mangaDir.chapterDirs) {
                        mangaDir.chapterDirs -= it
                    }
                }
            }
        }

        notifyChanges()
    }

    /**
     * Removes a manga that has been deleted from this cache.
     *
     * @param manga the manga to remove.
     */
    suspend fun removeManga(manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDirName = provider.getMangaDirName(manga.title, manga.url, manga.source)
            if (sourceDir.mangaDirs.containsKey(mangaDirName)) {
                sourceDir.mangaDirs -= mangaDirName
            }
        }

        notifyChanges()
    }

    suspend fun renameManga(manga: Manga, oldDir: UniFile, newTitle: String) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val oldName = oldDir.name ?: provider.getMangaDirName(manga.title, manga.url, manga.source)
            val mangaDir = sourceDir.mangaDirs[oldName] ?: return
            val newName = provider.getMangaDirName(newTitle, manga.url, manga.source)

            if (oldName == newName) return

            sourceDir.mangaDirs -= oldName
            sourceDir.mangaDirs += newName to mangaDir
        }

        notifyChanges()
    }

    suspend fun removeSource(source: MangaSource) {
        rootDownloadsDirMutex.withLock {
            rootDownloadsDir.sourceDirs -= source.id
        }

        notifyChanges()
    }

    fun requestReindex() {
        scope.launchIO { invalidateCache() }
    }

    @Synchronized
    fun invalidateCache() {
        legacyGeneration.incrementAndGet()
        legacyResults.clear()
        legacyNegativeChecks.clear()
        legacyChapters.clear()
        legacyMangaScanJob?.cancel()
        requestedLegacyManga.clear()
        legacyCounts.clear()
        while (true) {
            val deferred = deferredLegacyChecks.poll() ?: break
            legacyInFlight.remove(deferred)
            deferredLegacyCount.decrementAndGet()
        }
        legacyLibraryOwners = null
        provider.invalidateLegacyFolderIndex()
        lastRenew = 0L
        nextRenewAttempt = 0L
        renewalJob?.cancel()
        updateDiskCacheJob?.cancel()
        renewCache()
    }

    /**
     * Renews the downloads cache.
     */
    @Synchronized
    private fun renewCache() {
        // Avoid renewing cache if in the process nor too often
        val now = System.currentTimeMillis()
        if (!diskCacheReady.value || lastRenew + renewInterval >= now ||
            nextRenewAttempt > now || renewalJob?.isActive == true
        ) {
            return
        }

        nextRenewAttempt = now + 60_000L
        val generation = legacyGeneration.get()
        var indexedSuccessfully = false
        renewalJob = scope.launchIO {
            if (lastRenew == 0L) {
                _isInitializing.emit(true)
            }

            try {
                // Try to wait until extensions and sources have loaded
                val sources = withTimeoutOrNull(30.seconds) {
                    extensionManager.isInitialized.first { it }
                    sourceManager.isInitialized.first { it }
                    getSources()
                } ?: return@launchIO

                val sourceMap = sources.associate { provider.getSourceDirName(it).lowercase() to it.id }

                rootDownloadsDirMutex.withLock {
                    val updatedRootDir = RootDirectory(storageManager.getDownloadsDirectory())

                    updatedRootDir.sourceDirs = updatedRootDir.dir?.listFiles().orEmpty()
                        .filter { it.isDirectory && !it.name.isNullOrBlank() }
                        .mapNotNull { dir ->
                            val sourceId = sourceMap[dir.name!!.lowercase()]
                            sourceId?.let { it to SourceDirectory(dir) }
                        }
                        .toMap()

                    // Scanning many source folders in parallel creates excessive
                    // SAF I/O and heat. Limit indexing to two sources at a time.
                    updatedRootDir.sourceDirs.values.chunked(2).forEach { batch ->
                        batch.map { sourceDir ->
                            async {
                                sourceDir.mangaDirs = sourceDir.dir?.listFiles().orEmpty()
                                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                                    .associate { it.name!! to MangaDirectory(it) }
                                sourceDir.mangaDirs.values.forEach { itemDir ->
                                    itemDir.chapterDirs = itemDir.dir?.listFiles().orEmpty()
                                        .mapNotNull {
                                            when {
                                                it.name?.endsWith(MangaDownloader.TMP_DIR_SUFFIX) == true -> null

                                                it.isDirectory -> it.name

                                                it.isFile && it.extension.equals("cbz", ignoreCase = true) ->
                                                    it.nameWithoutExtension

                                                else -> null
                                            }
                                        }
                                        .toMutableSet()
                                }
                            }
                        }.awaitAll()
                    }

                    ensureActive()
                    if (generation == legacyGeneration.get()) {
                        rootDownloadsDir = updatedRootDir
                        indexedSuccessfully = true
                    }
                }
            } finally {
                _isInitializing.value = false
            }
        }.also {
            it.invokeOnCompletion { exception ->
                if (generation != legacyGeneration.get()) return@invokeOnCompletion
                if (exception != null && exception !is CancellationException) {
                    logcat(LogPriority.ERROR, exception) { "DownloadCache: failed to create cache" }
                }
                // An unavailable extension registry or failed SAF scan must
                // not freeze a stale/empty index for an entire hour.
                lastRenew = if (exception == null && indexedSuccessfully) System.currentTimeMillis() else 0L
                if (indexedSuccessfully && exception == null) notifyChanges() else _changes.trySend(Unit)
            }
        }
    }

    private fun getSources(): List<MangaSource> {
        return sourceManager.getOnlineSources() + sourceManager.getStubSources()
    }

    private fun notifyChanges() {
        // UI reads the latest download-index snapshot, not every intermediate
        // event. Conflate updates instead of spawning one coroutine per change.
        _changes.trySend(Unit)
        updateDiskCache()
    }

    private var updateDiskCacheJob: Job? = null
    private fun updateDiskCache() {
        updateDiskCacheJob?.cancel()
        updateDiskCacheJob = scope.launchIO {
            delay(1.seconds)
            ensureActive()
            // Snapshot under the same mutex used by index mutations, so an
            // in-flight download cannot produce a partially serialized tree.
            rootDownloadsDirMutex.withLock {
                ensureActive()
                try {
                    val bytes = ProtoBuf.encodeToByteArray(rootDownloadsDir)
                    val file = AtomicFile(diskCacheFile)
                    val stream = file.startWrite()
                    try {
                        stream.write(bytes)
                        file.finishWrite(stream)
                    } catch (failure: Throwable) {
                        file.failWrite(stream)
                        throw failure
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to write disk cache file" }
                }
            }
        }
    }
}

/**
 * Class to store the files under the root downloads directory.
 */
@Serializable
private class RootDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var sourceDirs: Map<Long, SourceDirectory> = mapOf(),
)

/**
 * Class to store the files under a source directory.
 */
@Serializable
private class SourceDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var mangaDirs: Map<String, MangaDirectory> = mapOf(),
)

/**
 * Class to store the files under a manga directory.
 */
@Serializable
private class MangaDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var chapterDirs: MutableSet<String> = mutableSetOf(),
)

private object UniFileAsStringSerializer : KSerializer<UniFile?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UniFile", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UniFile?) {
        return if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.uri.toString())
        }
    }

    override fun deserialize(decoder: Decoder): UniFile? {
        return if (decoder.decodeNotNullMark()) {
            UniFile.fromUri(Injekt.get<Context>(), decoder.decodeString().toUri())
        } else {
            decoder.decodeNull()
        }
    }
}
