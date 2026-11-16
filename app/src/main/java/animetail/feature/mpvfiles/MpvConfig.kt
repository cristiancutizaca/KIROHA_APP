package animetail.feature.mpvfiles

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.custombuttons.interactor.GetCustomButtons
import tachiyomi.domain.custombuttons.model.CustomButton
import tachiyomi.domain.storage.service.StorageManager
import java.io.IOException

@Inject
class MpvConfig(
    private val context: Context,
    private val storageManager: StorageManager,
    private val advancedPlayerPreferences: AdvancedPlayerPreferences,
    private val getCustomButtons: GetCustomButtons,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var copyJob: Job? = null
    private var lastCopy: FilesSnapshot? = null

    private data class FileStamp(val uri: String, val length: Long, val lastModified: Long)

    private data class FilesSnapshot(
        val directories: Map<String, List<FileStamp>>,
        val customButtons: List<CustomButton>,
    )

    @Synchronized
    fun copyFiles() {
        if (copyJob?.isActive == true) return

        copyJob = scope.launchIO {
            try {
                val files = buildMap {
                    put(MPV_FONTS_DIR, storageManager.getFontsDirectory().filesForCopy())
                    if (advancedPlayerPreferences.mpvUserFiles().get()) {
                        put(MPV_SCRIPTS_DIR, storageManager.getScriptsDirectory().filesForCopy())
                        put(MPV_SCRIPTS_OPTS_DIR, storageManager.getScriptOptsDirectory().filesForCopy())
                        put(MPV_SHADERS_DIR, storageManager.getShadersDirectory().filesForCopy())
                    }
                }
                val buttons = getCustomButtons.getAll()
                val snapshot = FilesSnapshot(
                    directories = files.mapValues { (_, entries) ->
                        entries.map { FileStamp(it.uri.toString(), it.length(), it.lastModified()) }
                    },
                    customButtons = buttons,
                )
                val hasStableMetadata = snapshot.directories.values.all { entries ->
                    entries.all { it.lastModified > 0L }
                }
                if (hasStableMetadata && lastCopy == snapshot) return@launchIO

                val mpvDir = getMpvDir()
                for (name in listOf(MPV_SCRIPTS_DIR, MPV_SCRIPTS_OPTS_DIR, MPV_SHADERS_DIR)) {
                    mpvDir.findFile(name)?.delete()
                    copyDirectoryFiles(mpvDir, name, files[name].orEmpty())
                }
                setupCustomButtons(buttons)
                mpvDir.findFile(MPV_SCRIPTS_DIR)?.createFile("aniyomi.lua")?.let { bridge ->
                    context.assets.open("aniyomi.lua").use { script ->
                        bridge.openOutputStream().use { script.copyTo(it) }
                    }
                }
                copyDirectoryFiles(mpvDir, MPV_FONTS_DIR, files.getValue(MPV_FONTS_DIR))
                writeFontsConf(mpvDir)
                lastCopy = snapshot
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A broken/missing script or SAF file must not kill the entire app
                // through an uncaught exception in this independent IO coroutine.
                logcat(LogPriority.ERROR, error) { "Could not refresh mpv files" }
            }
        }
    }

    private fun getMpvDir(): UniFile {
        return UniFile.fromFile(context.filesDir)!!.createDirectory(MPV_DIR)!!
    }

    private fun UniFile?.filesForCopy(): List<UniFile> =
        this?.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.uri.toString() }

    private suspend fun copyDirectoryFiles(mpvDir: UniFile, name: String, files: List<UniFile>) {
        if (files.isEmpty()) return
        val destination = mpvDir.createDirectory(name) ?: throw IOException("Could not prepare MPV directory: $name")
        for (source in files) {
            ensureActive()
            val target = destination.createFile(source.name) ?: throw IOException("Could not copy an MPV file")
            source.openInputStream().use { input ->
                target.openOutputStream().use { input.copyTo(it) }
            }
        }
    }

    fun setupCustomButtons(buttons: List<CustomButton>) {
        val scriptsDir = getMpvDir().createDirectory(MPV_SCRIPTS_DIR)!!
        val primaryButtonId = buttons.firstOrNull { it.isFavorite }?.id ?: 0L

        val customButtonsContent = buildString {
            appendLine(
                """
                local lua_modules = mp.find_config_file('scripts')
                if lua_modules then
                    package.path = package.path .. ';' .. lua_modules .. '/?.lua;' .. lua_modules .. '/?/init.lua;' .. '${scriptsDir.filePath}' .. '/?.lua'
                end
                local aniyomi = require 'aniyomi'
                """.trimIndent(),
            )

            buttons.forEach { button ->
                appendLine(
                    """
                    ${button.getButtonOnStartup(primaryButtonId)}
                    function button${button.id}()
                        ${button.getButtonContent(primaryButtonId)}
                    end
                    mp.register_script_message('call_button_${button.id}', button${button.id})
                    function button${button.id}long()
                        ${button.getButtonLongPressContent(primaryButtonId)}
                    end
                    mp.register_script_message('call_button_${button.id}_long', button${button.id}long)
                    """.trimIndent(),
                )
            }
        }

        val file = scriptsDir.createFile("custombuttons.lua")
        file?.openOutputStream()?.bufferedWriter()?.use {
            it.write(customButtonsContent)
        }
    }

    private fun writeFontsConf(mpvDir: UniFile) {
        val fontsDirectory = mpvDir.createDirectory(MPV_FONTS_DIR)!!.filePath!!
        val content = listOf(
            "<fontconfig>",
            "<dir>/system/fonts/</dir>",
            "<dir>/product/fonts/</dir>",
            "<dir>$fontsDirectory</dir>",
            "<cachedir>${context.cacheDir.path}</cachedir>",
            "<alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>",
            "<alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>",
            "<alias><family>Sans Serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>",
            "<alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>",
            "</fontconfig>",
        ).joinToString("\n")

        val config = mpvDir.createFile("fonts.conf") ?: throw IOException("Could not prepare fonts.conf")
        config.openOutputStream().bufferedWriter().use { it.write(content) }
    }

    private suspend inline fun ensureActive() {
        if (!currentCoroutineContext().isActive) {
            throw CancellationException()
        }
    }

    companion object {
        const val MPV_DIR = "mpv"
        const val MPV_FONTS_DIR = "fonts"
        const val MPV_SCRIPTS_DIR = "scripts"
        const val MPV_SCRIPTS_OPTS_DIR = "script-opts"
        const val MPV_SHADERS_DIR = "shaders"
    }
}
