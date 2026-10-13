package eu.kanade.tachiyomi.data.download.anime

import android.content.Context
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.anime.model.AnimeDownload
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.interactor.GetEpisode
import tachiyomi.domain.source.anime.service.AnimeSourceManager

/**
 * Durable anime queue snapshots. Manga and anime historically shared numeric preference keys,
 * allowing an episode to overwrite a chapter with the same database ID. Keys are now namespaced.
 *
 * Restore is non-destructive: missing sources or a process crash must not erase queued work.
 * Legacy numeric records are migrated only when they deserialize as anime entries.
 */
@Inject
@SingleIn(AppScope::class)
class AnimeDownloadStore(
    context: Context,
    private val sourceManager: AnimeSourceManager,
    private val json: Json,
    private val getAnime: GetAnime,
    private val getEpisode: GetEpisode,
) {
    private val preferences = context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE)
    private val prefix = "anime:"
    private val pausedKey = "anime:queue_paused"
    private var counter = 0

    private fun getKey(download: AnimeDownload): String = "$prefix${download.episode.id}"

    /** Commit queue changes before reporting them to the UI, including after unexpected process death. */
    @Synchronized
    fun addAll(downloads: List<AnimeDownload>) {
        if (downloads.isEmpty()) return
        preferences.edit(commit = true) {
            downloads.forEach { putString(getKey(it), serialize(it)) }
        }
    }

    @Synchronized
    fun remove(download: AnimeDownload) {
        preferences.edit(commit = true) { remove(getKey(download)) }
    }

    @Synchronized
    fun removeAll(downloads: List<AnimeDownload>) {
        preferences.edit(commit = true) {
            downloads.forEach { remove(getKey(it)) }
        }
    }

    /** Replace only this media type's entries, atomically, without deleting the other queue. */
    @Synchronized
    fun replaceAll(downloads: List<AnimeDownload>) {
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

    suspend fun restore(): List<AnimeDownload> {
        val snapshots = preferences.all.mapNotNull { (key, raw) ->
            if (!key.startsWith(prefix) && key.toLongOrNull() == null) return@mapNotNull null
            val encoded = raw as? String ?: return@mapNotNull null
            val item = deserialize(encoded) ?: return@mapNotNull null
            key to (encoded to item)
        }
        // Prefer an already migrated record over an old numeric key for the same item.
        val ordered = snapshots.sortedWith(
            compareBy<Pair<String, Pair<String, AnimeDownloadObject>>> { it.first.toLongOrNull() != null }
                .thenBy { it.second.second.order },
        ).distinctBy { it.second.second.episodeId }
        counter = maxOf(counter, (ordered.maxOfOrNull { it.second.second.order } ?: -1) + 1)
        // Upgrade legacy entries in a single durable edit. Never clear all entries on restore.
        val legacy = ordered.filter { it.first.toLongOrNull() != null }
        if (legacy.isNotEmpty()) {
            preferences.edit(commit = true) {
                legacy.forEach { (key, value) ->
                    val (raw, item) = value
                    val newKey = "$prefix${item.episodeId}"
                    if (!preferences.contains(newKey)) putString(newKey, raw)
                    remove(key)
                }
            }
        }

        val parents = mutableMapOf<Long, Anime?>()
        val restored = mutableListOf<AnimeDownload>()
        for ((_, value) in ordered.sortedBy { it.second.second.order }) {
            val item = value.second
            val parent = parents.getOrPut(item.animeId) {
                getAnime.await(item.animeId)
            } ?: continue
            val source = sourceManager.get(parent.source) as? AnimeHttpSource ?: continue
            val child = getEpisode.await(item.episodeId) ?: continue
            if (child.animeId != parent.id) continue
            restored.add(AnimeDownload(source, parent, child))
        }
        return restored
    }

    private fun serialize(download: AnimeDownload): String {
        return json.encodeToString(
            AnimeDownloadObject(download.anime.id, download.episode.id, counter++),
        )
    }

    private fun deserialize(value: String): AnimeDownloadObject? =
        try {
            json.decodeFromString<AnimeDownloadObject>(value)
        } catch (_: Exception) {
            null
        }
}

@Serializable
private data class AnimeDownloadObject(val animeId: Long, val episodeId: Long, val order: Int)
