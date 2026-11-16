package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.util.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
import tachiyomi.domain.library.manga.LibraryManga
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.entries.manga.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import eu.kanade.tachiyomi.data.download.manga.LegacyMangaIndex.Key as LegacyMangaKey

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
    private val storageManager: StorageManager,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    private data class CachedLegacyManga(
        val title: String,
        val originalTitle: String,
        val downloads: LegacyMangaDownloads,
    )

    private data class LegacyIndexState(
        val rootUri: String,
        val records: MutableMap<LegacyMangaKey, LegacyMangaIndex.Record> = mutableMapOf(),
        var dirty: Boolean = false,
    )

    private val processState = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
    private val legacyScanner = LegacyMangaDownloadScanner(context, provider)
    private val legacyIndex = LegacyMangaIndex(context)
    private var legacyIndexState: LegacyIndexState? = null
    private val legacyIndexWrites = Channel<Unit>(Channel.CONFLATED)
    private val legacyDownloads = ConcurrentHashMap<LegacyMangaKey, CachedLegacyManga>()
    private val legacyLookupRequests = Channel<LegacyMangaKey>(64)
    private val legacyLookupInFlight = ConcurrentHashMap.newKeySet<LegacyMangaKey>()
    private val legacyScanPermit = Semaphore(1)
    private val legacyLibraryUpdates = Channel<Unit>(Channel.CONFLATED)
    private val legacyGeneration = AtomicLong(0)
    private var legacyLibraryRequest: Map<LegacyMangaKey, LegacyMangaIndex.LibraryEntry>? = null
    private var lastLegacyLibrary: List<LibraryManga>? = null
    private var legacyLibraryScanJob: Job? = null
    private val legacyMatchesFromLibrary = mutableSetOf<LegacyMangaKey>()

    init {
        // Attempt to read cache file
        scope.launchIO {
            try {
                rootDownloadsDirMutex.withLock {
                    rootDownloadsDir = RootDirectory(storageManager.getDownloadsDirectory())
                    rootDownloadsDir.dir?.uri?.toString()?.let { rootUri ->
                        val records = legacyIndex.read(rootUri)
                        synchronized(this@MangaDownloadCache) {
                            if (legacyIndexState == null) {
                                legacyIndexState = LegacyIndexState(rootUri, records.toMutableMap())
                            }
                        }
                    }
                    if (diskCacheFile.exists()) {
                        val diskCache = AtomicFile(diskCacheFile).openRead().use {
                            ProtoBuf.decodeFromByteArray<RootDirectory>(it.readBytes())
                        }
                        if (diskCache.dir?.uri == rootDownloadsDir.dir?.uri && diskCache.dir != null) {
                            rootDownloadsDir = diskCache
                            lastRenew = diskCacheFile.lastModified()
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
                _changes.trySend(Unit)
            }
        }

        scope.launchIO {
            for (key in legacyLookupRequests) {
                try {
                    processState.first { it.isAtLeast(Lifecycle.State.STARTED) }
                    sourceManager.isInitialized.first { it }
                    val manga = mangaRepository.getMangaByUrlAndSourceId(key.mangaUrl, key.sourceId) ?: continue
                    val source = sourceManager.getOrStub(key.sourceId)
                    val generation = legacyGeneration.get()
                    val refreshed = scanLegacyManga(manga, source)
                    cacheLegacyResult(key, refreshed, generation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logcat(LogPriority.WARN, error) { "Could not check legacy manga downloads" }
                } finally {
                    legacyLookupInFlight.remove(key)
                }
            }
        }

        // Publish verified downloads during long scans without rebuilding the grid for every match.
        legacyLibraryUpdates.receiveAsFlow()
            .onEach {
                _changes.trySend(Unit)
                delay(500.milliseconds)
            }
            .launchIn(scope)

        legacyIndexWrites.receiveAsFlow()
            .onEach {
                val snapshot = synchronized(this) {
                    legacyIndexState?.takeIf { it.dirty }?.let {
                        it.dirty = false
                        it.rootUri to it.records.toMap()
                    }
                }
                snapshot?.let { (root, records) -> legacyIndex.write(root, records) }
            }
            .launchIn(scope)

        processState.map { it.isAtLeast(Lifecycle.State.STARTED) }
            .distinctUntilChanged()
            .onEach { visible ->
                if (!visible) {
                    synchronized(this) {
                        if (legacyLibraryScanJob?.isActive == true) {
                            legacyLibraryRequest = null
                            legacyLibraryScanJob?.cancel()
                        }
                    }
                    legacyIndexWrites.trySend(Unit)
                }
                _changes.trySend(Unit)
            }
            .launchIn(scope)

        sourceManager.isInitialized
            .filter { it }
            .onEach {
                renewCache()
                _changes.trySend(Unit)
            }
            .launchIn(scope)

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
        val key = LegacyMangaKey(sourceId, mangaUrl)
        val cachedLegacy = legacyDownloads[key]?.takeIf { it.title == mangaTitle }?.downloads
        if (skipCache) {
            val source = sourceManager.getOrStub(sourceId)
            val current = provider.findChapterDir(
                chapterName,
                chapterScanlator,
                chapterUrl,
                mangaTitle,
                source,
                mangaUrl,
            )
            return current != null || cachedLegacy?.files?.get(chapterUrl)?.resolve(context)?.exists() == true
        }

        renewCache()
        val current = getCurrentChapterNames(sourceId, mangaTitle, mangaUrl)
        if (current.isNotEmpty() &&
            provider.getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl).any { it in current }
        ) {
            return true
        }
        if (cachedLegacy?.files?.containsKey(chapterUrl) == true) return true
        if (cachedLegacy != null && chapterUrl in cachedLegacy.chapterUrls) return false

        val mayHaveLegacy = cachedLegacy != null || !provider.isLegacyMangaTitleKnownAbsent(mangaTitle, sourceId)
        if (mayHaveLegacy && legacyLookupInFlight.add(key)) {
            if (legacyLookupRequests.trySend(key).isFailure) legacyLookupInFlight.remove(key)
        }
        return false
    }

    /** Resolve the local file even if the library has not finished indexing it yet. */
    suspend fun resolveChapterFile(manga: Manga, chapter: Chapter, source: MangaSource): UniFile? {
        val current = provider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
            manga.url,
        )
        if (current != null || manga.url.isBlank() || manga.isLocal()) return current

        val key = LegacyMangaKey(manga)
        val cached = legacyDownloads[key]?.takeIf { it.title == manga.title && it.originalTitle == manga.ogTitle }
        val cachedMatch = cached?.downloads?.files?.get(chapter.url)
        val legacyFile = cachedMatch?.resolve(context)
        if (legacyFile?.exists() == true) return legacyFile
        if (cachedMatch == null && cached != null && chapter.url in cached.downloads.chapterUrls) return null

        val generation = legacyGeneration.get()
        val scanned = try {
            scanLegacyManga(manga, source, chapter)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Could not read an older manga download" }
            return null
        }
        if (!cacheLegacyResult(key, scanned, generation)) return null
        return scanned.downloads.files[chapter.url]?.resolve(context)
    }

    @Synchronized
    private fun cacheLegacyResult(key: LegacyMangaKey, result: CachedLegacyManga, generation: Long): Boolean {
        if (generation != legacyGeneration.get()) return false
        if (legacyDownloads.put(key, result) != result) _changes.trySend(Unit)
        return true
    }

    private suspend fun scanLegacyManga(
        manga: Manga,
        source: MangaSource,
        openedChapter: Chapter? = null,
    ): CachedLegacyManga = legacyScanPermit.withPermit {
        val root = storageManager.getDownloadsDirectory()
        val folders = root?.let { provider.findLegacyMangaFolders(it, manga, source) }.orEmpty()
        if (root == null || folders.isEmpty()) {
            val checked = setOfNotNull(openedChapter?.url)
            return@withPermit CachedLegacyManga(
                manga.title,
                manga.ogTitle,
                LegacyMangaDownloads(checked, emptyMap(), 0),
            )
        }

        val knownChapters = chapterRepository.getChapterByMangaId(manga.id)
        val chapters = if (openedChapter == null || knownChapters.any { it.url == openedChapter.url }) {
            knownChapters
        } else {
            knownChapters + openedChapter
        }
        val ownership = if (manga.favorite || chapters.size == 1) {
            val names = legacyTitles(manga)
            val key = LegacyMangaKey(manga)
            val otherOwners = mangaRepository.getMangaFavorites().asSequence()
                .filter { LegacyMangaKey(it) != key && !it.isLocal() }
                .filter { legacyTitles(it).any(names::contains) }
                .flatMap { provider.findLegacyMangaFolders(root, it, sourceManager.getOrStub(it.source)) }
                .mapTo(hashSetOf()) { it.uri }
            folders.mapNotNullTo(hashSetOf()) { it.uri.takeUnless(otherOwners::contains) }
        } else {
            emptySet()
        }
        CachedLegacyManga(manga.title, manga.ogTitle, legacyScanner.scan(manga, source, chapters, folders, ownership))
    }

    private fun legacyTitles(manga: Manga): Set<String> =
        provider.legacyTitleNames(manga.title) + provider.legacyTitleNames(manga.ogTitle)

    private fun getCurrentChapterNames(sourceId: Long, title: String, mangaUrl: String): Set<String> {
        val sourceDir = rootDownloadsDir.sourceDirs[sourceId] ?: return emptySet()
        return sourceDir.mangaDirs[provider.getMangaDirName(title, mangaUrl, sourceId)]?.chapterDirs.orEmpty()
    }

    fun getTotalDownloadCount(): Int {
        renewCache()
        val current = rootDownloadsDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.mangaDirs.values.sumOf { it.chapterDirs.size }
        }
        return current + legacyDownloads.entries.sumOf { (key, entry) ->
            val chapterNames = getCurrentChapterNames(key.sourceId, entry.title, key.mangaUrl)
            entry.downloads.additionalCount(chapterNames.mapTo(hashSetOf()) { it.lowercase() })
        }
    }

    fun getDownloadCount(manga: Manga): Int {
        renewCache()
        return getCachedDownloadCount(manga)
    }

    internal fun getCachedDownloadCount(manga: Manga): Int {
        if (manga.url.isBlank()) return 0
        val current = getCurrentChapterNames(manga.source, manga.title, manga.url)
        val legacy = legacyDownloads[LegacyMangaKey(manga)]?.takeIf {
            it.title == manga.title && it.originalTitle == manga.ogTitle
        }?.downloads ?: return current.size
        return current.size + legacy.additionalCount(current.mapTo(hashSetOf()) { it.lowercase() })
    }

    /**
     * Inspect candidate folders once for a library version. The scanner can
     * count owned files before a restored favorite has chapter rows.
     */
    @Synchronized
    fun checkLegacyDownloads(library: List<LibraryManga>): Boolean {
        renewCache()
        val waitingForInitialIndex = !diskCacheReady.value || (lastRenew == 0L && renewalJob?.isActive == true)
        if (!diskCacheReady.value || !sourceManager.isInitialized.value) return waitingForInitialIndex
        if (!processState.value.isAtLeast(Lifecycle.State.STARTED)) return waitingForInitialIndex
        if (library === lastLegacyLibrary && legacyLibraryRequest != null) {
            return legacyLibraryScanJob?.isActive == true || waitingForInitialIndex
        }

        lastLegacyLibrary = library
        val entries = library.asSequence().filter { !it.manga.isLocal() && it.manga.url.isNotBlank() }
            .associateBy { LegacyMangaKey(it.manga) }
        val request = entries.mapValues { (_, entry) ->
            LegacyMangaIndex.LibraryEntry(
                entry.manga.title,
                entry.manga.ogTitle,
                entry.totalChapters,
                entry.chapterFetchedAt,
            )
        }
        if (request != legacyLibraryRequest) {
            legacyLibraryRequest = request
            legacyLibraryScanJob?.cancel()
            val generation = legacyGeneration.get()
            if (entries.isNotEmpty()) {
                legacyLibraryScanJob = scope.launchIO {
                    try {
                        val scans = scanLegacyLibrary(entries, request) { rootUri, key, entry, record ->
                            synchronized(this@MangaDownloadCache) {
                                if (generation == legacyGeneration.get() && legacyLibraryRequest === request) {
                                    legacyMatchesFromLibrary.add(key)
                                    val previous = legacyDownloads.put(key, entry)
                                    val showsDownloads = entry.downloads.hasFiles ||
                                        previous?.downloads?.hasFiles == true
                                    if (showsDownloads && previous != entry) legacyLibraryUpdates.trySend(Unit)
                                    updateLegacyIndexRecord(rootUri, key, record)
                                }
                            }
                        }
                        synchronized(this@MangaDownloadCache) {
                            if (generation == legacyGeneration.get() && legacyLibraryRequest === request) {
                                val removedFavorites = legacyMatchesFromLibrary - request.keys
                                for (key in removedFavorites) legacyDownloads.remove(key)
                                for (key in request.keys) {
                                    val entry = scans[key]
                                    if (entry == null) legacyDownloads.remove(key) else legacyDownloads[key] = entry
                                }
                                legacyMatchesFromLibrary.clear()
                                legacyMatchesFromLibrary.addAll(scans.keys)
                                legacyIndexState?.let {
                                    if (it.records.keys.retainAll(scans.keys)) it.dirty = true
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logcat(LogPriority.WARN, error) { "Could not index legacy library downloads" }
                    }
                }.also { job ->
                    job.invokeOnCompletion {
                        legacyIndexWrites.trySend(Unit)
                        _changes.trySend(Unit)
                    }
                }
            } else {
                legacyMatchesFromLibrary.forEach(legacyDownloads::remove)
                legacyMatchesFromLibrary.clear()
                legacyLibraryScanJob = null
                legacyIndexState?.takeIf { it.records.isNotEmpty() }?.let {
                    it.records.clear()
                    it.dirty = true
                    legacyIndexWrites.trySend(Unit)
                }
            }
        }
        return legacyLibraryScanJob?.isActive == true || waitingForInitialIndex
    }

    private fun updateLegacyIndexRecord(rootUri: String, key: LegacyMangaKey, record: LegacyMangaIndex.Record?) {
        val index = legacyIndexState?.takeIf { it.rootUri == rootUri }
            ?: LegacyIndexState(rootUri).also { legacyIndexState = it }
        val previous = if (record == null) index.records.remove(key) else index.records.put(key, record)
        if (previous != record) index.dirty = true
    }

    private suspend fun scanLegacyLibrary(
        entries: Map<LegacyMangaKey, LibraryManga>,
        request: Map<LegacyMangaKey, LegacyMangaIndex.LibraryEntry>,
        onVerified: (String, LegacyMangaKey, CachedLegacyManga, LegacyMangaIndex.Record?) -> Unit,
    ): Map<LegacyMangaKey, CachedLegacyManga> {
        val root = storageManager.getDownloadsDirectory() ?: return emptyMap()
        val rootUri = root.uri.toString()
        val existing = synchronized(this) {
            legacyIndexState?.takeIf { it.rootUri == rootUri }?.records?.toMap().orEmpty()
        }
        val sources = mutableMapOf<Long, MangaSource>()
        val work = entries.values.mapNotNull { entry ->
            currentCoroutineContext().ensureActive()
            val manga = entry.manga
            val source = sources.getOrPut(manga.source) { sourceManager.getOrStub(manga.source) }
            val folders = provider.findLegacyMangaFolders(root, manga, source)
            if (folders.isEmpty()) null else Triple(manga, source, folders)
        }
        val owners = mutableMapOf<Uri, MutableSet<LegacyMangaKey>>()
        for ((manga, _, folders) in work) {
            for (folder in folders) owners.getOrPut(folder.uri) { mutableSetOf() }.add(LegacyMangaKey(manga))
        }

        return buildMap {
            for ((manga, source, folders) in work) {
                currentCoroutineContext().ensureActive()
                val key = LegacyMangaKey(manga)
                val uniqueFolders = folders.mapNotNullTo(hashSetOf()) {
                    it.uri.takeIf { uri -> owners[uri]?.singleOrNull() == key }
                }
                try {
                    val stamps = folders.associate {
                        it.uri.toString() to LegacyMangaIndex.Folder(it.lastModified(), it.uri in uniqueFolders)
                    }
                    val libraryEntry = request.getValue(key)
                    val now = System.currentTimeMillis()
                    val saved = existing[key]?.takeIf { it.canReuse(libraryEntry, stamps, now) }
                    val downloads = saved?.restoreDownloads() ?: legacyScanPermit.withPermit {
                        val chapters = chapterRepository.getChapterByMangaId(manga.id)
                        legacyScanner.scan(manga, source, chapters, folders, uniqueFolders)
                    }
                    val entry = CachedLegacyManga(manga.title, manga.ogTitle, downloads)
                    put(key, entry)
                    val record = saved ?: downloads.takeIf { it.hasFiles }?.let {
                        LegacyMangaIndex.Record.from(libraryEntry, stamps, now, it)
                    }
                    onVerified(rootUri, key, entry, record)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logcat(LogPriority.WARN, error) { "Could not check an older manga download folder" }
                }
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
            val names = provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).toSet()
            mangaDir.chapterDirs -= names
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
            val names = chapters.flatMapTo(hashSetOf()) { chapter ->
                provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
            }
            mangaDir.chapterDirs -= names
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
        scope.launchIO { invalidateCache(keepLegacyDownloads = true) }
    }

    @Synchronized
    fun invalidateCache(keepLegacyDownloads: Boolean = false) {
        legacyGeneration.incrementAndGet()
        if (!keepLegacyDownloads) {
            legacyDownloads.clear()
            legacyMatchesFromLibrary.clear()
        }
        legacyLibraryScanJob?.cancel()
        legacyLibraryRequest = null
        legacyIndexState = rootDownloadsDir.dir?.uri?.toString()?.let {
            LegacyIndexState(it, dirty = true)
        }
        legacyIndexWrites.trySend(Unit)
        provider.invalidateLegacyFolderIndex()
        lastRenew = 0L
        nextRenewAttempt = 0L
        renewalJob?.cancel()
        updateDiskCacheJob?.cancel()
        renewCache()
        _changes.trySend(Unit)
    }

    /**
     * Renews the downloads cache.
     */
    @Synchronized
    private fun renewCache() {
        // Avoid renewing cache if in the process nor too often
        val now = System.currentTimeMillis()
        if (!diskCacheReady.value || !sourceManager.isInitialized.value || lastRenew + renewInterval >= now ||
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
                val sourceMap = getSources().associate { provider.getSourceDirName(it).lowercase() to it.id }

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
                                        .toSet()
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
                // Retry failed filesystem scans without the normal one-hour interval.
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
    @Volatile var sourceDirs: Map<Long, SourceDirectory> = emptyMap(),
)

/**
 * Class to store the files under a source directory.
 */
@Serializable
private class SourceDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    @Volatile var mangaDirs: Map<String, MangaDirectory> = emptyMap(),
)

/**
 * Class to store the files under a manga directory.
 */
@Serializable
private class MangaDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    @Volatile var chapterDirs: Set<String> = emptySet(),
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
