package eu.kanade.tachiyomi.network

/** Keep legacy automatic User-Agent preferences in step with Android System WebView. */
object UserAgentCompatibility {
    const val LEGACY_DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    fun select(configured: String, systemWebViewUserAgent: String): String {
        val saved = configured.trim()
        if (saved.isNotEmpty() && saved != LEGACY_DEFAULT_USER_AGENT) return saved

        // WebView can be upgraded independently of Android. Do not keep
        // advertising Chrome 133 while the embedded browser uses a newer Chrome.
        val inferred = systemWebViewUserAgent
            .replace(Regex("; Android .*?\\)"), "; Android 10; K)")
            .replace(Regex("Version/.* Chrome/"), "Chrome/")
            .trim()
        return inferred.takeIf { it.startsWith("Mozilla/") && "Chrome/" in it }
            ?: saved.ifBlank { LEGACY_DEFAULT_USER_AGENT }
    }
}
