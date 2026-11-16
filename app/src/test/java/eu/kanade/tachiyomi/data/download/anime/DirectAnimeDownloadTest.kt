package eu.kanade.tachiyomi.data.download.anime

import android.text.TextUtils
import android.util.Log
import com.hippo.unifile.UniFile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class DirectAnimeDownloadTest {

    @TempDir
    lateinit var downloads: File

    private val content = ByteArray(512 * 1024) { it.toByte() }.apply {
        "ftyp".encodeToByteArray().copyInto(this, destinationOffset = 4)
    }
    private val split = 128 * 1024
    private val client by lazy { OkHttpClient.Builder().retryOnConnectionFailure(false).build() }

    @BeforeEach
    fun stubAndroidUtilities() {
        mockkStatic(TextUtils::class, Log::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
        every { Log.isLoggable(any(), any()) } returns false
    }

    @AfterEach
    fun resetAndroidUtilities() {
        unmockkStatic(TextUtils::class, Log::class)
    }

    @Test
    fun resumesTheRemainingBytesAfterAnInterruptedDownload() = runBlocking<Unit>(Dispatchers.IO) {
        val ranges = CopyOnWriteArrayList<String>()
        val ifRanges = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            val range = exchange.requestHeaders.getFirst("Range").orEmpty()
            ranges += range
            ifRanges += exchange.requestHeaders.getFirst("If-Range").orEmpty()
            if (range.isEmpty()) {
                exchange.interruptVideo()
            } else {
                exchange.responseHeaders.add("Content-Range", "bytes $split-${content.lastIndex}/${content.size}")
                exchange.sendVideo(206, content.copyOfRange(split, content.size))
            }
        }) { url ->
            val interrupted = runCatching { download(url).download() }.exceptionOrNull()
            assertInstanceOf(IOException::class.java, interrupted)
            assertEquals(split.toLong(), downloads.resolve(DirectAnimeDownload.PART_FILE).length())

            val progress = mutableListOf<Int>()
            val result = download(url, progress::add).download()
            assertEquals("Episode.mp4", result?.name)
            assertArrayEquals(content, downloads.resolve("Episode.mp4").readBytes())
            assertEquals(listOf("", "bytes=$split-"), ranges)
            assertEquals(listOf("", "\"episode-v1\""), ifRanges)
            assertTrue(progress.first() > 0)
            assertEquals(99, progress.last())
            assertEquals(listOf("Episode.mp4"), downloads.list()?.toList())
        }
    }

    @Test
    fun handlesAnIgnoredRangeWithoutAppendingTheFullResponse() = runBlocking<Unit>(Dispatchers.IO) {
        val count = AtomicInteger()
        withServer({ exchange ->
            if (count.getAndIncrement() == 0) exchange.interruptVideo() else exchange.sendVideo(200, content)
        }) { url ->
            assertInstanceOf(IOException::class.java, runCatching { download(url).download() }.exceptionOrNull())
            assertEquals("Episode.mp4", download(url).download()?.name)
            assertArrayEquals(content, downloads.resolve("Episode.mp4").readBytes())
            assertEquals(2, count.get())
        }
    }

    @Test
    fun retriesWithAFullRequestWhenTheServerReturnsTheWrongRange() = runBlocking<Unit>(Dispatchers.IO) {
        val ranges = CopyOnWriteArrayList<String>()
        val count = AtomicInteger()
        withServer({ exchange ->
            ranges += exchange.requestHeaders.getFirst("Range").orEmpty()
            when (count.getAndIncrement()) {
                0 -> exchange.interruptVideo()

                1 -> {
                    val wrongOffset = split + 1
                    exchange.responseHeaders.add(
                        "Content-Range",
                        "bytes $wrongOffset-${content.lastIndex}/${content.size}",
                    )
                    exchange.sendVideo(206, content.copyOfRange(wrongOffset, content.size))
                }

                else -> exchange.sendVideo(200, content)
            }
        }) { url ->
            assertInstanceOf(IOException::class.java, runCatching { download(url).download() }.exceptionOrNull())
            assertEquals("Episode.mp4", download(url).download()?.name)
            assertArrayEquals(content, downloads.resolve("Episode.mp4").readBytes())
            assertEquals(listOf("", "bytes=$split-", ""), ranges)
        }
    }

    @Test
    fun leavesAPlaylistForFfmpegEvenIfItIsLabelledAsVideo() = runBlocking<Unit>(Dispatchers.IO) {
        withServer({ exchange ->
            exchange.sendVideo(200, "#EXTM3U\n#EXT-X-ENDLIST\n".encodeToByteArray())
        }) { url ->
            assertNull(download(url).download())
            assertTrue(downloads.list().orEmpty().isEmpty())
        }
    }

    private fun download(url: String, progress: (Int) -> Unit = {}): DirectAnimeDownload =
        DirectAnimeDownload(
            client,
            Request.Builder().url(url).build(),
            UniFile.fromFile(downloads)!!,
            "Episode",
            progress,
        )

    private fun HttpExchange.interruptVideo() {
        responseHeaders.add("Content-Type", "video/mp4")
        responseHeaders.add("ETag", "\"episode-v1\"")
        responseHeaders.add("Connection", "close")
        sendResponseHeaders(200, content.size.toLong())
        responseBody.write(content, 0, split)
        responseBody.flush()
    }

    private fun HttpExchange.sendVideo(code: Int, bytes: ByteArray) {
        responseHeaders.add("Content-Type", "video/mp4")
        responseHeaders.add("ETag", "\"episode-v1\"")
        sendResponseHeaders(code, bytes.size.toLong())
        runCatching { responseBody.use { it.write(bytes) } }
    }

    private suspend fun withServer(handler: (HttpExchange) -> Unit, test: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/episode") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            test("http://127.0.0.1:${server.address.port}/episode")
        } finally {
            server.stop(0)
        }
    }
}
