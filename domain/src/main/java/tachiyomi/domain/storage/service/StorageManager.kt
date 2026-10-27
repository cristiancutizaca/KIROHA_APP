package tachiyomi.domain.storage.service

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import tachiyomi.core.common.storage.FolderProvider
import java.io.File

@Inject
@SingleIn(AppScope::class)
class StorageManager(
    private val context: Context,
    scope: CoroutineScope,
    storagePreferences: StoragePreferences,
    private val folderProvider: FolderProvider,
) {
    private val storageDirPreference = storagePreferences.baseStorageDirectory
    @Volatile
    private var baseDir: UniFile? = getBaseDir(storageDirPreference.get())

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .flowOn(Dispatchers.IO)
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storageDirPreference.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDir = getBaseDir(uri)
                baseDir?.let { parent ->
                    parent.createDirectory(AUTOMATIC_BACKUPS_PATH)
                    parent.createDirectory(LOCAL_SOURCE_PATH)
                    parent.createDirectory(LOCAL_ANIMESOURCE_PATH)
                    parent.createDirectory(DOWNLOADS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                    parent.createDirectory(MPV_CONFIG_PATH)?.let { mpvDir ->
                        mpvDir.createDirectory(FONTS_PATH)
                        mpvDir.createDirectory(SCRIPTS_PATH)
                        mpvDir.createDirectory(SCRIPT_OPTS_PATH)
                        mpvDir.createDirectory(SHADERS_PATH)
                    }
                }
                _changes.send(Unit)
            }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        // The URI may belong to a removed volume or a revoked SAF grant.
        // Do not let a stale storage preference crash startup.
        return runCatching {
            UniFile.fromUri(context, uri.toUri())
                ?.takeIf { it.exists() && it.isDirectory }
        }.getOrNull()
    }

    fun getAutomaticBackupsDirectory(): UniFile? {
        return baseDir?.createDirectory(AUTOMATIC_BACKUPS_PATH)
    }

    fun getDownloadsDirectory(): UniFile? {
        // Recheck the saved URI on demand when a grant has been revoked and
        // reselected. The preference itself may not emit if the URI is the same.
        val root = baseDir ?: getBaseDir(storageDirPreference.get()).also { baseDir = it }
        return try {
            if (root == null || !root.canWrite()) {
                baseDir = null
                null
            } else {
                root.createDirectory(DOWNLOADS_PATH).also {
                    if (it == null) baseDir = null
                }
            }
        } catch (_: SecurityException) {
            baseDir = null
            null
        }
    }

    fun getLocalMangaSourceDirectory(): UniFile? {
        return baseDir?.createDirectory(LOCAL_SOURCE_PATH)
    }

    fun getLocalAnimeSourceDirectory(): UniFile? {
        return baseDir?.createDirectory(LOCAL_ANIMESOURCE_PATH)
    }

    fun getFontsDirectory(): UniFile? {
        return getMPVConfigDirectory()?.createDirectory(FONTS_PATH)
    }

    fun getScriptsDirectory(): UniFile? {
        return getMPVConfigDirectory()?.createDirectory(SCRIPTS_PATH)
    }

    fun getScriptOptsDirectory(): UniFile? {
        return getMPVConfigDirectory()?.createDirectory(SCRIPT_OPTS_PATH)
    }

    fun getShadersDirectory(): UniFile? {
        return getMPVConfigDirectory()?.createDirectory(SHADERS_PATH)
    }

    fun getMPVConfigDirectory(): UniFile? {
        return baseDir?.createDirectory(MPV_CONFIG_PATH)
    }
}

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"
private const val LOCAL_ANIMESOURCE_PATH = "localanime"
private const val MPV_CONFIG_PATH = "mpv-config"
private const val FONTS_PATH = "fonts"
const val SCRIPTS_PATH = "scripts"
const val SCRIPT_OPTS_PATH = "script-opts"
private const val SHADERS_PATH = "shaders"
