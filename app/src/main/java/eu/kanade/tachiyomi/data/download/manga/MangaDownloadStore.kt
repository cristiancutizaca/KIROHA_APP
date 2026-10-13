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

/**
 * Durable manga queue snapshots. Manga and anime historically shared numeric preference keys,
 * allowing an episode to overwrite a chapter with the same database ID. Keys are now namespaced.
 *
 * Restore is non-destructive: missing sources or a process crash must not erase queued work.
 * Legacy numeric records are migrated only when they deserialize as manga entries.
 */
@Inject
@SingleIn(AppScope::class)
class MangaDownloadStore(
    context: Context,
    private val sourceManager: MangaSourceManager,
    private val json: Json,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
) {
    private val preferences = context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE)
    private val prefix = "manga:"
    private val pausedKey = "manga:queue_paused"
    private var counter = 0

    private fun getKey(download: MangaDownload): String = "$prefix${download.chapter.id}"

    /** Commit queue changes before reporting them to the UI, including after unexpected process death. */
    @Synchronized
    fun addAll(downloads: List<MangaDownload>) {
        if (downloads.isEmpty()) return
        preferences.edit(commit = true) {
            downloads.forEach { putString(getKey(it), serialize(it)) }
        }
    }

    @Synchronized
    fun remove(download: MangaDownload) {
        preferences.edit(commit = true) { remove(getKey(download)) }
    }

    @Synchronized
    fun removeAll(downloads: List<MangaDownload>) {
        preferences.edit(commit = true) {
            downloads.forEach { remove(getKey(it)) }
        }
    }

    /** Replace only this media type's entries, atomically, without deleting the other queue. */
    @Synchronized
    fun replaceAll(downloads: List<MangaDownload>) {
        preferences.edit(commit = true) {
            preferences.all.forEach { (key, raw) ->
                if (key.startsWith(prefix) && key != pausedKey ||
                    key.toLongOrNull() != null && (raw as? String)?.let(::deserialize) != null
                ) {
                    remove(key)
                }
            }
            downloads.forEach { putString(getKey(it), serialize(it)) }
        }
    }

    @Synchronized
    fun clear() = replaceAll(emptyList())

    fun isPaused(): Boolean = preferences.getBoolean(pausedKey, false)

    fun setPaused(paused: Boolean) {
        preferences.edit(commit = true) { putBoolean(pausedKey, paused) }
    }

    suspend fun restore(): List<MangaDownload> {
        val snapshots = preferences.all.mapNotNull { (key, raw) ->
            if (!key.startsWith(prefix) && key.toLongOrNull() == null) return@mapNotNull null
            val encoded = raw as? String ?: return@mapNotNull null
            val item = deserialize(encoded) ?: return@mapNotNull null
            key to (encoded to item)
        }
        // Prefer an already migrated record over an old numeric key for the same item.
        val ordered = snapshots.sortedWith(
            compareBy<Pair<String, Pair<String, DownloadObject>>> { it.first.toLongOrNull() != null }
                .thenBy { it.second.second.order },
        ).distinctBy { it.second.second.chapterId }
        counter = maxOf(counter, (ordered.maxOfOrNull { it.second.second.order } ?: -1) + 1)
        // Upgrade legacy entries in a single durable edit. Never clear all entries on restore.
        val legacy = ordered.filter { it.first.toLongOrNull() != null }
        if (legacy.isNotEmpty()) {
            preferences.edit(commit = true) {
                legacy.forEach { (key, value) ->
                    val (raw, item) = value
                    val newKey = "$prefix${item.chapterId}"
                    if (!preferences.contains(newKey)) putString(newKey, raw)
                    remove(key)
                }
            }
        }

        val parents = mutableMapOf<Long, Manga?>()
        val restored = mutableListOf<MangaDownload>()
        for ((_, value) in ordered.sortedBy { it.second.second.order }) {
            val item = value.second
            val parent = parents.getOrPut(item.mangaId) {
                getManga.await(item.mangaId)
            } ?: continue
            val source = sourceManager.get(parent.source) as? HttpSource ?: continue
            val child = getChapter.await(item.chapterId) ?: continue
            if (child.mangaId != parent.id) continue
            restored.add(MangaDownload(source, parent, child))
        }
        return restored
    }

    private fun serialize(download: MangaDownload): String {
        return json.encodeToString(
            DownloadObject(download.manga.id, download.chapter.id, counter++),
        )
    }

    private fun deserialize(value: String): DownloadObject? =
        try {
            json.decodeFromString<DownloadObject>(value)
        } catch (_: Exception) {
            null
        }
}

@Serializable
private data class DownloadObject(val mangaId: Long, val chapterId: Long, val order: Int)
