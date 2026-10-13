package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.manga.model.MangaDownload
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.interactor.GetChapter
import tachiyomi.domain.source.manga.service.MangaSourceManager

@Inject
@SingleIn(AppScope::class)
class MangaDownloadStore(
    context: Context,
    private val sourceManager: MangaSourceManager,
    private val json: Json,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
) {

    private val preferences = context.getSharedPreferences("active_manga_downloads", Context.MODE_PRIVATE)
    // Older builds stored anime and manga in the same file and used overlapping numeric IDs.
    // Read it for a one-time best-effort migration, but never clear it from either downloader.
    private val legacyPreferences = context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE)
    private val legacyMigrationKey = "legacy_manga_queue_migrated"
    private val pausedKey = "manga_queue_paused"

    fun isPaused(): Boolean = preferences.getBoolean(pausedKey, false)

    fun setPaused(paused: Boolean) {
        preferences.edit(commit = true) { putBoolean(pausedKey, paused) }
    }

    private var counter = 0

    fun addAll(downloads: List<MangaDownload>) {
        preferences.edit(commit = true) {
            downloads.forEach { putString(getKey(it), serialize(it)) }
        }
    }

    fun remove(download: MangaDownload) {
        preferences.edit(commit = true) {
            remove(getKey(download))
        }
    }

    fun removeAll(downloads: List<MangaDownload>) {
        preferences.edit(commit = true) {
            downloads.forEach { remove(getKey(it)) }
        }
    }

    /** One atomic durable snapshot for queue reorder, without deleting migration metadata. */
    @Synchronized
    fun replaceAll(downloads: List<MangaDownload>) {
        preferences.edit(commit = true) {
            preferences.all.keys.filter { it.toLongOrNull() != null }.forEach { remove(it) }
            downloads.forEach { putString(getKey(it), serialize(it)) }
        }
    }

    fun clear() = replaceAll(emptyList())

    private fun getKey(download: MangaDownload): String {
        return download.chapter.id.toString()
    }

    suspend fun restore(): List<MangaDownload> {
        // Never clear a persistent queue while restoring it. A crash between clear()
        // and re-enqueue used to permanently lose all pending downloads.
        val saved = preferences.all.values
            .mapNotNull { it as? String }
            .mapNotNull(::deserialize)
        val migrating = !preferences.getBoolean(legacyMigrationKey, false)
        val legacy = if (migrating) {
            legacyPreferences.all.values
                .mapNotNull { it as? String }
                .mapNotNull(::deserialize)
        } else {
            emptyList()
        }

        val restored = mutableListOf<MangaDownload>()
        val validLegacy = mutableListOf<DownloadObject>()
        val cachedManga = mutableMapOf<Long, Manga?>()
        for (obj in (saved + legacy).distinctBy { it.chapterId }.sortedBy { it.order }) {
            val manga = cachedManga.getOrPut(obj.mangaId) {
                getManga.await(obj.mangaId)
            } ?: continue
            val source = sourceManager.get(manga.source) as? HttpSource ?: continue
            val chapter = getChapter.await(obj.chapterId) ?: continue
            // Disambiguate legacy entries: chapter and episode IDs overlap.
            if (chapter.mangaId != manga.id) continue
            restored.add(MangaDownload(source, manga, chapter))
            if (obj in legacy) validLegacy.add(obj)
        }

        if (migrating) {
            preferences.edit(commit = true) {
                validLegacy.forEach { obj ->
                    val key = obj.chapterId.toString()
                    if (!preferences.contains(key)) putString(key, json.encodeToString(obj))
                }
                putBoolean(legacyMigrationKey, true)
            }
        }
        // Preserve queue ordering across process restarts and new additions.
        counter = (saved + legacy).maxOfOrNull { it.order }?.plus(1) ?: 0
        return restored
    }

    private fun serialize(download: MangaDownload): String {
        val obj = DownloadObject(download.manga.id, download.chapter.id, counter++)
        return json.encodeToString(obj)
    }

    private fun deserialize(string: String): DownloadObject? {
        return try {
            json.decodeFromString<DownloadObject>(string)
        } catch (e: Exception) {
            null
        }
    }
}

@Serializable
private data class DownloadObject(val mangaId: Long, val chapterId: Long, val order: Int)
