package eu.kanade.tachiyomi.ui.main

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ResumeSessionTest {
    private val values = mutableMapOf<String, String>()
    private val preferences = mockk<SharedPreferences> {
        every { getString(any(), null) } answers { values[firstArg<String>()] }
        every { edit() } returns mockk<SharedPreferences.Editor> {
            every { putString(any(), any()) } answers {
                val key = firstArg<String>()
                val value = secondArg<String?>()
                if (value == null) values.remove(key) else values[key] = value
                this@mockk
            }
            every { remove(any()) } answers {
                values.remove(firstArg<String>())
                this@mockk
            }
            every { clear() } answers {
                values.clear()
                this@mockk
            }
            every { apply() } returns Unit
        }
    }

    private val context = mockk<Context> {
        every { getSharedPreferences(any(), Context.MODE_PRIVATE) } returns preferences
    }

    @Test
    fun restoresTheChapterAndPageInANewSessionAndClearsThemAfterLeavingTheReader() {
        val main = ResumeSession.MainPosition("MANGA", mangaId = 18)
        val reader = ResumeSession.ReaderPosition(18, 25, 7)
        ResumeSession(context).apply {
            saveMain(main)
            saveReader(reader)
        }

        val restarted = ResumeSession(context)
        assertEquals(main, restarted.mainPosition())
        assertEquals(reader, restarted.readerPosition())
        restarted.saveReader(null)
        assertEquals(main, ResumeSession(context).mainPosition())
        assertNull(ResumeSession(context).readerPosition())
    }

    @Test
    fun keepsTheMostRecentMainScreenInsteadOfReopeningAnOldReader() {
        val initial = ResumeSession(context)
        initial.saveReader(ResumeSession.ReaderPosition(18, 25, 7))
        val recent = ResumeSession.MainPosition("BROWSE", sourceId = 42, sourceQuery = "search")
        initial.saveMain(recent)
        val restarted = ResumeSession(context)
        assertEquals(recent, restarted.mainPosition())
        assertNull(restarted.readerPosition())
        restarted.clear()
        assertNull(ResumeSession(context).mainPosition())
    }
}
