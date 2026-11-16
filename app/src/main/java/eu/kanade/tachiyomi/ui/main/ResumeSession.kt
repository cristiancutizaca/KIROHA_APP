package eu.kanade.tachiyomi.ui.main

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Fallback for a launcher start after Android has removed the task and its saved state. */
internal class ResumeSession(context: Context) {
    private val preferences = context.getSharedPreferences("kiroha_resume_session", Context.MODE_PRIVATE)

    fun mainPosition(): MainPosition? = read(KEY_MAIN)

    fun readerPosition(): ReaderPosition? = read<ReaderPosition>(KEY_READER)?.takeIf { it.isValid }

    fun saveMain(position: MainPosition) {
        preferences.edit()
            .putString(KEY_MAIN, Json.encodeToString(position))
            .remove(KEY_READER)
            .apply()
    }

    fun saveReader(position: ReaderPosition?) {
        preferences.edit().putString(KEY_READER, position?.let { Json.encodeToString(it) }).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private inline fun <reified T> read(key: String): T? {
        val value = preferences.getString(key, null) ?: return null
        return runCatching { Json.decodeFromString<T>(value) }.getOrNull()
    }

    @Serializable
    data class MainPosition(
        val tab: String?,
        val mangaId: Long? = null,
        val mangaFromSource: Boolean = false,
        val sourceId: Long? = null,
        val sourceQuery: String? = null,
    )

    @Serializable
    data class ReaderPosition(val mangaId: Long, val chapterId: Long, val page: Int) {
        val isValid: Boolean get() = mangaId >= 0L && chapterId >= 0L && page >= 0
    }

    private companion object {
        const val KEY_MAIN = "main"
        const val KEY_READER = "reader"
    }
}
