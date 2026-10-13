package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import tachiyomi.data.kiroha.KirohaDatabaseRepository
import kiroha.core.AndroidKeyValueStore
import kiroha.core.DeviceRegistry
import kiroha.core.LocalSyncJournal
import kiroha.core.SyncClient
import kiroha.core.SyncEntity
import kiroha.core.SyncEvent
import tachiyomi.domain.items.chapter.interactor.GetChapter
import tachiyomi.domain.items.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.items.chapter.interactor.UpdateChapter
import tachiyomi.domain.items.chapter.model.ChapterUpdate
import tachiyomi.domain.entries.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.items.episode.interactor.GetEpisode
import tachiyomi.domain.items.episode.interactor.GetEpisodeByUrlAndAnimeId
import tachiyomi.domain.items.episode.interactor.UpdateEpisode
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.domain.entries.anime.interactor.GetAnimeByUrlAndSourceId
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph

/** Stores non-secret endpoint metadata and encrypts the device token with Android Keystore. */
object KirohaSyncConfig {
    private const val PREFS = "kiroha_sync_transport_v1"
    private const val SERVER_URL = "server_url"
    private const val SPACE_ID = "space_id"
    private const val TOKEN = "device_token"
    private const val IV = "device_token_iv"
    private const val KEY_ALIAS = "kiroha.sync.device.token"

    data class Value(val serverUrl: String, val spaceId: String, val deviceToken: String)

    fun save(context: Context, serverUrl: String, spaceId: String, deviceToken: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = encrypt(deviceToken)
        check(prefs.edit()
            .putString(SERVER_URL, serverUrl.trimEnd('/'))
            .putString(SPACE_ID, spaceId)
            .putString(TOKEN, Base64.getEncoder().encodeToString(encrypted.first))
            .putString(IV, Base64.getEncoder().encodeToString(encrypted.second))
            .commit())
    }

    fun read(context: Context): Value? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(SERVER_URL, null)?.takeIf(String::isNotBlank) ?: return null
        val space = prefs.getString(SPACE_ID, null)?.takeIf(String::isNotBlank) ?: return null
        val ciphertext = prefs.getString(TOKEN, null)?.let(Base64.getDecoder()::decode) ?: return null
        val iv = prefs.getString(IV, null)?.let(Base64.getDecoder()::decode) ?: return null
        return Value(url, space, decrypt(ciphertext, iv))
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        WorkManager.getInstance(context).cancelUniqueWork("kiroha-sync")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)) to cipher.iv
    }

    private fun decrypt(value: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(value), StandardCharsets.UTF_8)
    }
}

class KirohaSyncJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var database: KirohaDatabaseRepository
    @Inject private lateinit var getChapter: GetChapter
    @Inject private lateinit var getChapterByUrlAndMangaId: GetChapterByUrlAndMangaId
    @Inject private lateinit var updateChapter: UpdateChapter
    @Inject private lateinit var getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId
    @Inject private lateinit var getEpisode: GetEpisode
    @Inject private lateinit var getEpisodeByUrlAndAnimeId: GetEpisodeByUrlAndAnimeId
    @Inject private lateinit var updateEpisode: UpdateEpisode
    @Inject private lateinit var getAnimeByUrlAndSourceId: GetAnimeByUrlAndSourceId

    init {
        graph.inject(this)
    }

    override suspend fun doWork(): Result {
        val config = KirohaSyncConfig.read(applicationContext) ?: return Result.failure()
        val device = DeviceRegistry(kiroha.core.AndroidKeyValueStore(applicationContext)).load() ?: return Result.failure()
        val repositoryCursor = database.cursor(config.spaceId, device.deviceId)
        val backend = AndroidHttpSyncBackend(config.serverUrl, config.deviceToken)
        val journal = LocalSyncJournal(AndroidKeyValueStore(applicationContext))
        val client = SyncClient(
            spaceId = config.spaceId,
            deviceId = device.deviceId,
            backend = backend,
            initialCursor = repositoryCursor ?: kiroha.core.SyncCursor(config.spaceId, device.deviceId),
        )
        return try {
            backend.heartbeat(config.spaceId)
            val legacyPending = journal.pending()
            val sqlPending = database.pendingEvents()
            val pending = (sqlPending + legacyPending).distinctBy { it.eventId }
            val pushed = client.push(pending)
            if (pushed.isNotEmpty()) {
                val acknowledged = pushed.map { it.event.eventId }.toSet()
                journal.acknowledge(acknowledged)
                acknowledged.forEach { database.acknowledgeEvent(it) }
            }
            val pulled = client.pull { }
            pulled.forEach { pulledEvent ->
                database.insertRemoteEvent(pulledEvent.event)
                applyRemoteProgress(pulledEvent.event)
                applyRemoteDownload(pulledEvent.event)
            }
            database.upsertCursor(client.cursor)
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: RuntimeException) {
            Result.retry()
        }
    }

    private suspend fun applyRemoteProgress(event: SyncEvent) {
        if (event.entity != SyncEntity.PROGRESS) return
        val fields = event.payload.split(';')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0] to it[1] }
        val position = fields["position"]?.toLongOrNull() ?: return
        when (fields["kind"]) {
            "MANGA_PAGE" -> {
                val source = fields["sourceNamespace"]?.toLongOrNull() ?: return
                val manga = fields["mangaUrl"]?.let { getMangaByUrlAndSourceId.await(it, source) } ?: return
                val chapter = fields["canonicalId"]?.let { getChapterByUrlAndMangaId.await(it, manga.id) } ?: return
                updateChapter.await(ChapterUpdate(id = chapter.id, lastPageRead = position, read = fields["read"]?.toBoolean() ?: chapter.read))
            }
            "VIDEO_POSITION" -> {
                val source = fields["sourceNamespace"]?.toLongOrNull() ?: return
                val anime = fields["animeUrl"]?.let { getAnimeByUrlAndSourceId.await(it, source) } ?: return
                val episode = fields["canonicalId"]?.let { getEpisodeByUrlAndAnimeId.await(it, anime.id) } ?: return
                val duration = fields["duration"]?.toLongOrNull() ?: episode.totalSeconds
                updateEpisode.await(
                    EpisodeUpdate(
                        id = episode.id,
                        seen = episode.seen,
                        bookmark = episode.bookmark,
                        fillermark = episode.fillermark,
                        lastSecondSeen = position,
                        totalSeconds = duration,
                        // Remote completion is independent from the timestamp.
                        // A later rewatch must not silently clear a completed state.
                        dateFetch = null,
                    ),
                )
                if (fields["seen"]?.toBoolean() == true && !episode.seen) {
                    updateEpisode.await(EpisodeUpdate(id = episode.id, seen = true, lastSecondSeen = position, totalSeconds = duration))
                }
            }
        }
    }

    private suspend fun applyRemoteDownload(event: SyncEvent) {
        if (event.entity != SyncEntity.DOWNLOAD) return
        val fields = event.payload.split(';')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0] to it[1] }
        val itemId = fields["itemId"] ?: event.entityId
        val remoteState = when (fields["state"]) {
            "AVAILABLE_LOCAL" -> kiroha.core.DownloadAvailability.AVAILABLE_REMOTE
            "STORAGE_UNAVAILABLE", "MISSING" -> kiroha.core.DownloadAvailability.LAST_KNOWN_REMOTE
            "DELETED" -> kiroha.core.DownloadAvailability.DELETED
            else -> return
        }
        database.upsertDownload(
            kiroha.core.DownloadRecord(
                itemId = itemId,
                deviceId = event.deviceId,
                storageId = fields["storageLabel"] ?: "remote",
                state = remoteState,
                downloadedEver = fields["downloadedEver"]?.toBoolean() == true,
                localPath = null,
                size = fields["size"]?.toLongOrNull() ?: 0L,
                downloadedAt = null,
                lastVerifiedAt = fields["lastVerifiedAt"]?.toLongOrNull(),
                sourceNamespace = fields["sourceNamespace"],
                canonicalId = fields["canonicalId"],
            ),
        )
    }

    companion object {
        private const val UNIQUE_NAME = "kiroha-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<KirohaSyncJob>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Coalesces bursts from page/video/download events into one network attempt. */
        fun enqueueDebounced(context: Context) {
            val request = OneTimeWorkRequestBuilder<KirohaSyncJob>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<KirohaSyncJob>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("$UNIQUE_NAME-periodic")
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "$UNIQUE_NAME-periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
