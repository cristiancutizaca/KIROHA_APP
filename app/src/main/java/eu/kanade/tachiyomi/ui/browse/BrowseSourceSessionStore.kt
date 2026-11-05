package eu.kanade.tachiyomi.ui.browse

import android.content.Context

/**
 * Remembers the last plain-text extension search after Android reclaims the
 * process while the user is away. This is independent from Paging's in-memory
 * results: we restore the search, then let the extension reload the pages.
 *
 * Do not persist source filters here: extensions own mutable, often
 * non-serializable filter objects. Also never call this from incognito mode.
 */
object BrowseSourceSessionStore {
    private const val PREFS_NAME = "kiroha_source_search_sessions_v1"
    private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_QUERY_LENGTH = 2_048

    fun restore(context: Context, media: String, sourceId: Long, explicitQuery: String?): String? {
        if (explicitQuery != null) return explicitQuery
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "$media.$sourceId"
        val savedAt = prefs.getLong("$key.time", 0L)
        val age = System.currentTimeMillis() - savedAt
        if (savedAt <= 0L || age < 0L || age > MAX_AGE_MILLIS) return null
        return prefs.getString("$key.query", null)
    }

    fun remember(context: Context, media: String, sourceId: Long, query: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "$media.$sourceId"
        prefs.edit().apply {
            if (query.isNullOrBlank() || query.length > MAX_QUERY_LENGTH) {
                remove("$key.time")
                remove("$key.query")
            } else {
                putString("$key.query", query)
                putLong("$key.time", System.currentTimeMillis())
            }
        }.apply()
    }
}
