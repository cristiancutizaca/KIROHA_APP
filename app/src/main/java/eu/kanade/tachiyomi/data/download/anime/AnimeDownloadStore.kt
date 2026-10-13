package eu.kanade.tachiyomi.data.download.anime

import android.content.Context
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.download.anime.model.AnimeDownload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.interactor.GetEpisode
import tachiyomi.domain.source.anime.service.AnimeSourceManager

/**
 * This class is used to persist active downloads across application restarts.
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

    /**
     * Preference file where active downloads are stored.
     */
    private val preferences = context.getSharedPreferences("active_anime_downloads", Context.MODE_PRIVATE)
    private val legacyPreferences = context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE)
    private val legacyMigrationKey = "legacy_anime_queue_migrated"

    /**
     * Counter used to keep the queue order.
     */
    private var counter = 0

    /**
     * Adds a list of downloads to the store.
     *
     * @param downloads the list of downloads to add.
     */
    fun addAll(downloads: List<AnimeDownload>) {
        preferences.edit(commit = true) {
            downloads.forEach { putString(getKey(it), serialize(it)) }
        }
    }

    /**
     * Removes a download from the store.
     *
     * @param download the download to remove.
     */
    fun remove(download: AnimeDownload) {
        preferences.edit(commit = true) {
            remove(getKey(download))
        }
    }

    /**
     * Removes a list of downloads from the store.
     *
     * @param downloads the download to remove.
     */
    fun removeAll(downloads: List<AnimeDownload>) {
        preferences.edit(commit = true) {
            downloads.forEach { remove(getKey(it)) }
        }
    }

    /**
     * Removes all the downloads from the store.
     */
    /** One atomic durable snapshot for queue reorder, without deleting migration metadata. */
    @Synchronized
    fun replaceAll(downloads: List<AnimeDownload>) {
        preferences.edit(commit = true) {
            preferences.all.keys.filter { it.toLongOrNull() != null }.forEach { remove(it) }
            downloads.forEach { putString(getKey(it), serialize(it)) }
        }
    }

    fun clear() = replaceAll(emptyList())

    /**
     * Returns the preference's key for the given download.
     *
     * @param download the download.
     */
    private fun getKey(download: AnimeDownload): String {
        return download.episode.id.toString()
    }

    /**
     * Returns the list of downloads to restore. It should be called in a background thread.
     */
    fun restore(): List<AnimeDownload> {
        // Keep entries on disk throughout restore. The previous clear() introduced
        // a window where a killed process lost every pending download.
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

        val restored = mutableListOf<AnimeDownload>()
        val validLegacy = mutableListOf<AnimeDownloadObject>()
        val cachedAnime = mutableMapOf<Long, Anime?>()
        for (obj in (saved + legacy).distinctBy { it.episodeId }.sortedBy { it.order }) {
            val anime = cachedAnime.getOrPut(obj.animeId) {
                runBlocking { getAnime.await(obj.animeId) }
            } ?: continue
            val source = sourceManager.get(anime.source) as? AnimeHttpSource ?: continue
            val episode = runBlocking { getEpisode.await(obj.episodeId) } ?: continue
            if (episode.animeId != anime.id) continue
            restored.add(AnimeDownload(source, anime, episode))
            if (obj in legacy) validLegacy.add(obj)
        }

        if (migrating) {
            preferences.edit(commit = true) {
                validLegacy.forEach { obj ->
                    val key = obj.episodeId.toString()
                    if (!preferences.contains(key)) putString(key, json.encodeToString(obj))
                }
                putBoolean(legacyMigrationKey, true)
            }
        }
        counter = (saved + legacy).maxOfOrNull { it.order }?.plus(1) ?: 0
        return restored
    }

    /**
     * Converts a download to a string.
     *
     * @param download the download to serialize.
     */
    private fun serialize(download: AnimeDownload): String {
        val obj = AnimeDownloadObject(download.anime.id, download.episode.id, counter++)
        return json.encodeToString(obj)
    }

    /**
     * Restore a download from a string.
     *
     * @param string the download as string.
     */
    private fun deserialize(string: String): AnimeDownloadObject? {
        return try {
            json.decodeFromString<AnimeDownloadObject>(string)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Class used for download serialization
 *
 * @param animeId the id of the anime.
 * @param episodeId the id of the episode.
 * @param order the order of the download in the queue.
 */
@Serializable
private data class AnimeDownloadObject(val animeId: Long, val episodeId: Long, val order: Int)
