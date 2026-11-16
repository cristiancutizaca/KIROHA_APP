package eu.kanade.tachiyomi.data.download.anime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FfmpegDownloadProgressTest {

    @Test
    fun usesDurationFromSplitInputLogsWithoutASecondProbe() {
        val progress = FfmpegDownloadProgress()
        progress.readLog("  Duration: ")
        progress.readLog("00:02:00")
        assertNull(progress.percent(60_000.0))
        progress.readLog(".00")
        progress.readLog(", start: 0.0")
        assertEquals(50, progress.percent(60_000.0))
        assertEquals(99, progress.percent(120_000.0))
    }

    @Test
    fun handlesUnknownDurationAndKeepsTheFirstValidDuration() {
        val progress = FfmpegDownloadProgress()
        progress.readLog("  Duration: N/A, start: 0.0")
        assertNull(progress.percent(60_000.0))
        progress.readLog("  Duration: 00:02:00.40, start: 0.0")
        progress.readLog("  Duration: 00:20:00.00, start: 0.0")
        assertEquals(50, progress.percent(60_200.0))
    }
}
