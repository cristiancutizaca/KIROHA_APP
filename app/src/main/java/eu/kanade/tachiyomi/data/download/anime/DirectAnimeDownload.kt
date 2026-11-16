package eu.kanade.tachiyomi.data.download.anime

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Copies a complete HTTP video in its original container to the episode's temporary directory. */
internal class DirectAnimeDownload(
    client: OkHttpClient,
    request: Request,
    private val directory: UniFile,
    private val filename: String,
    private val onProgress: (Int) -> Unit,
) {
    private val client = client.newBuilder()
        .cache(null)
        .callTimeout(0L, TimeUnit.MILLISECONDS)
        .readTimeout(45L, TimeUnit.SECONDS)
        .build()
    private val request = request.newBuilder().header("Accept-Encoding", "identity").build()
    private val requestKey = request.url.toString().hashUrl()

    suspend fun download(): UniFile? {
        val context = currentCoroutineContext()
        val partial = directory.findFile(PART_FILE)
        val saved = partial?.let { readResume() }
        val offset = partial?.length() ?: 0L
        val resume = saved?.takeIf { offset in 1..it.length }
        if (resume != null && offset == resume.length) {
            context.ensureActive()
            return complete(partial, resume.extension, resume.length)
        }

        if (resume != null) {
            val ranged = request.newBuilder()
                .header("Range", "bytes=$offset-")
                .header("If-Range", resume.etag)
                .build()
            var retryFromStart = false
            val result = execute(ranged) { response ->
                when {
                    response.code == 206 && response.matchesResume(resume, offset) ->
                        copyVideo(response, partial, resume.extension, resume.length, offset, context)

                    response.code == 200 -> copyFullVideo(response, context)

                    response.code == 206 || response.code == 416 -> {
                        retryFromStart = true
                        null
                    }

                    response.code == 429 || response.code in 500..599 -> throw IOException(
                        "Video HTTP ${response.code}",
                    )

                    else -> null
                }
            }
            if (!retryFromStart) return result
            // An unexpected byte range must never be appended to a partial episode.
            clearResume()
        }

        return execute(request) { response ->
            if (response.code == 200) copyFullVideo(response, context) else null
        }
    }

    private fun copyFullVideo(response: Response, context: CoroutineContext): UniFile? {
        val length = response.body.contentLength().takeIf { it >= HEADER_BYTES } ?: return null
        if (!response.isIdentityEncoded()) return null
        val extension = response.detectContainer() ?: return null
        clearResume()
        val previous = directory.findFile(PART_FILE)
        if (previous != null && !previous.delete()) throw IOException("Could not replace the incomplete anime video")
        val file = directory.createFile(PART_FILE) ?: throw IOException("Could not create the temporary anime video")

        return copyVideo(response, file, extension, length, 0L, context)
    }

    private fun copyVideo(
        response: Response,
        file: UniFile,
        extension: String,
        length: Long,
        offset: Long,
        context: CoroutineContext,
    ): UniFile {
        context.ensureActive()
        file.openOutputStream(offset > 0L).use { output ->
            if (offset == 0L) {
                response.header("ETag")?.takeIf { it.isStrongEtag() }?.let {
                    writeResume(Resume(requestKey, response.request.url.toString().hashUrl(), it, length, extension))
                }
            }
            response.body.byteStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                var written = offset
                var previousPercent = percentage(written, length)
                onProgress(previousPercent)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count > length - written) throw IOException("Video response exceeded its declared length")
                    output.write(buffer, 0, count)
                    written += count
                    val percent = percentage(written, length)
                    if (percent != previousPercent) {
                        onProgress(percent)
                        previousPercent = percent
                    }
                }
                if (written != length) throw IOException("The video connection ended before the episode was complete")
            }
        }
        context.ensureActive()
        return complete(file, extension, length)
    }

    private fun complete(file: UniFile, extension: String, expectedLength: Long): UniFile {
        if (file.length() != expectedLength || !file.renameTo("$filename.$extension")) {
            throw IOException("Could not finalize the anime video")
        }
        directory.findFile(RESUME_FILE)?.delete()
        return directory.findFile("$filename.$extension") ?: throw IOException("Completed anime video is missing")
    }

    private fun readResume(): Resume? {
        val file = directory.findFile(RESUME_FILE)?.takeIf { it.length() in 1..4096 } ?: return null
        return runCatching {
            Json.decodeFromString<Resume>(file.openInputStream().bufferedReader().use { it.readText() })
        }.getOrNull()?.takeIf {
            it.requestKey == requestKey && it.etag.isStrongEtag() &&
                it.length >= HEADER_BYTES && it.extension in VIDEO_EXTENSIONS
        }
    }

    private fun writeResume(resume: Resume) {
        val file = directory.createFile(RESUME_FILE) ?: return
        file.openOutputStream().bufferedWriter().use { it.write(Json.encodeToString(resume)) }
    }

    private fun clearResume() {
        val old = directory.findFile(RESUME_FILE) ?: return
        if (!old.delete()) throw IOException("Could not reset the anime video transfer")
    }

    private suspend fun <T> execute(request: Request, read: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        // Call.await() cancels header retrieval. Body reads also need cancellation while paused.
        val cancelCall = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.await().use(read)
        } finally {
            cancelCall.cancel()
        }
    }

    private fun Response.matchesResume(saved: Resume, offset: Long): Boolean {
        val remaining = saved.length - offset
        val responseLength = body.contentLength()
        return isIdentityEncoded() &&
            request.url.toString().hashUrl() == saved.responseKey &&
            header("ETag") == saved.etag &&
            header("Content-Range") == "bytes $offset-${saved.length - 1}/${saved.length}" &&
            (responseLength == -1L || responseLength == remaining)
    }

    private fun Response.isIdentityEncoded(): Boolean =
        header("Content-Encoding")?.equals("identity", ignoreCase = true) != false

    private fun Response.detectContainer(): String? {
        val source = body.source()
        if (!source.request(HEADER_BYTES)) return null
        return when {
            source.buffer.rangeEquals(4L, MP4_MAGIC) -> "mp4"

            source.buffer.rangeEquals(0L, EBML_MAGIC) -> {
                if (body.contentType()?.subtype == "webm" || request.url.encodedPath.endsWith(".webm", true)) {
                    "webm"
                } else {
                    "mkv"
                }
            }

            else -> null
        }
    }

    private fun String.isStrongEtag(): Boolean = length > 2 && first() == '"' && last() == '"'

    private fun String.hashUrl(): String = encodeUtf8().sha256().hex()

    private fun percentage(bytes: Long, total: Long): Int =
        (bytes.toDouble() / total * 100).toInt().coerceIn(0, 99)

    @Serializable
    private data class Resume(
        val requestKey: String,
        val responseKey: String,
        val etag: String,
        val length: Long,
        val extension: String,
    )

    companion object {
        const val PART_FILE = "kiroha_video.part"
        const val RESUME_FILE = "kiroha_video.resume"
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm")
        private const val HEADER_BYTES = 8L
        private val MP4_MAGIC = "ftyp".encodeUtf8()
        private val EBML_MAGIC = "1a45dfa3".decodeHex()
    }
}
