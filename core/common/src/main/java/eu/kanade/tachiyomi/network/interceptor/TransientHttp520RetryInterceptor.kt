package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * A single bounded retry for transient origin errors returned as HTTP 520.
 * GET requests only: never replay POST/API writes or retry forever.
 * A persistent 520 still reaches the source, so users see the real error.
 */
class TransientHttp520RetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!shouldRetry(request.method, response.code)) return response

        response.close()
        try {
            Thread.sleep(RETRY_DELAY_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while retrying HTTP 520", interrupted)
        }
        return chain.proceed(request)
    }

    internal companion object {
        private const val RETRY_DELAY_MS = 650L

        fun shouldRetry(method: String, code: Int): Boolean = method == "GET" && code == 520
    }
}
