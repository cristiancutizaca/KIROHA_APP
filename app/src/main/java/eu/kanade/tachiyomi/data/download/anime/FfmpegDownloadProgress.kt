package eu.kanade.tachiyomi.data.download.anime

/** Learns the duration from the active input instead of opening a second connection with FFprobe. */
internal class FfmpegDownloadProgress {
    @Volatile
    private var durationMillis = 0L
    private var pendingDuration: String? = null

    @Synchronized
    fun readLog(message: String) {
        if (durationMillis != 0L) return
        val marker = message.indexOf(DURATION_PREFIX)
        val value = if (marker >= 0) {
            message.substring(marker + DURATION_PREFIX.length).take(MAX_DURATION_LOG_LENGTH)
        } else {
            val previous = pendingDuration ?: return
            previous + message.take(MAX_DURATION_LOG_LENGTH - previous.length)
        }
        val time = DURATION.find(value)?.groupValues
        if (time != null) {
            val hours = time[1].toLong()
            val minutes = time[2].toLong()
            val seconds = time[3].toLong()
            val millis = time[4].padEnd(3, '0').toLong()
            durationMillis = ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
        }
        pendingDuration = value.takeIf {
            time == null && ',' !in it && '\n' !in it && it.length < MAX_DURATION_LOG_LENGTH
        }
    }

    fun percent(outputTimeMillis: Double): Int? {
        val duration = durationMillis
        if (duration == 0L || outputTimeMillis <= 0) return null
        return (outputTimeMillis / duration * 100).toInt().coerceIn(0, 99)
    }

    private companion object {
        const val DURATION_PREFIX = "Duration:"
        const val MAX_DURATION_LOG_LENGTH = 80
        val DURATION = Regex("""^\s*(\d{1,6}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?\s*[,\n]""")
    }
}
