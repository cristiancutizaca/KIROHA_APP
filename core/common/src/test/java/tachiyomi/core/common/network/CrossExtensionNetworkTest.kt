package tachiyomi.core.common.network

import eu.kanade.tachiyomi.network.UserAgentCompatibility
import eu.kanade.tachiyomi.network.interceptor.TransientHttp520RetryInterceptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrossExtensionNetworkTest {
    @Test
    fun `legacy User-Agent tracks current WebView version`() {
        val webViewUa =
            "Mozilla/5.0 (Linux; Android 16; Phone Build/A; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/151.0.0.0 Mobile Safari/537.36"
        val result = UserAgentCompatibility.select(UserAgentCompatibility.LEGACY_DEFAULT_USER_AGENT, webViewUa)
        assertTrue(result.contains("Chrome/151.0.0.0"))
        assertFalse(result.contains("Version/4.0"))
        assertFalse(result.contains("; wv)"))
    }

    @Test
    fun `custom User-Agent remains untouched`() {
        assertEquals("My-Custom-Agent", UserAgentCompatibility.select("My-Custom-Agent", "Chrome/151"))
    }

    @Test
    fun `WebView unavailable keeps a nonempty fallback User-Agent`() {
        assertEquals(
            UserAgentCompatibility.LEGACY_DEFAULT_USER_AGENT,
            UserAgentCompatibility.select("", ""),
        )
    }

    @Test
    fun `HTTP 520 retry is bounded to safe GET requests`() {
        assertTrue(TransientHttp520RetryInterceptor.shouldRetry("GET", 520))
        assertFalse(TransientHttp520RetryInterceptor.shouldRetry("POST", 520))
        assertFalse(TransientHttp520RetryInterceptor.shouldRetry("GET", 403))
        assertFalse(TransientHttp520RetryInterceptor.shouldRetry("GET", 200))
    }
}
