package eu.kanade.tachiyomi.ui.library.manga

import androidx.lifecycle.viewModelScope
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.library.manga.LibraryManga
import tachiyomi.domain.library.manga.model.MangaLibraryGroup
import tachiyomi.domain.library.manga.model.MangaLibrarySort
import tachiyomi.domain.source.manga.model.StubMangaSource

class MangaLibraryViewModelTest {

    @Test
    fun downloadedFilterUsesAvailableCountsWhileLegacyDownloadsAreStillBeingChecked() = runBlocking<Unit> {
        val category = Category(1, "Manga", 0, 0, false)
        val library = (1L..3L).map { id ->
            LibraryManga(
                manga = Manga.create().copy(id = id, source = 42, ogTitle = "Manga $id", url = "/manga/$id"),
                category = category.id,
                totalChapters = 1,
                readCount = 0,
                bookmarkCount = 0,
                latestUpload = 0,
                chapterFetchedAt = 0,
                lastRead = 0,
            )
        }
        val filter = preference(TriState.DISABLED)
        val counts = MutableStateFlow(mapOf(1L to 1))
        val changes = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val source = StubMangaSource(42, "en", "Test")

        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = MangaLibraryViewModel(
                getLibraryManga = mockk { every { subscribe() } returns flowOf(library) },
                getCategories = mockk { every { subscribe() } returns flowOf(listOf(category)) },
                getTracksPerManga = mockk { every { subscribe() } returns flowOf(emptyMap()) },
                getNextChapters = mockk(),
                getChaptersByMangaId = mockk(),
                setReadStatus = mockk(),
                updateManga = mockk(),
                setMangaCategories = mockk(),
                preferences = mockk { every { downloadedOnly } returns preference(false) },
                libraryPreferences = mockk {
                    every { lastUsedCategory } returns preference(0)
                    every { downloadBadge } returns preference(false)
                    every { unreadBadge } returns preference(false)
                    every { localBadge } returns preference(false)
                    every { languageBadge } returns preference(false)
                    every { autoUpdateMangaRestrictions } returns preference(emptySet())
                    every { filterDownloadedManga } returns filter
                    every { filterUnread } returns preference(TriState.DISABLED)
                    every { filterStarted } returns preference(TriState.DISABLED)
                    every { filterBookmarked } returns preference(TriState.DISABLED)
                    every { filterCompleted } returns preference(TriState.DISABLED)
                    every { filterIntervalCustom } returns preference(TriState.DISABLED)
                    every { mangaSortingMode } returns preference(MangaLibrarySort.default)
                    every { categoryTabs } returns preference(true)
                    every { categoryNumberOfItems } returns preference(false)
                    every { showContinueReadingButton } returns preference(false)
                    every { groupMangaLibraryBy } returns preference(MangaLibraryGroup.BY_DEFAULT)
                },
                coverCache = mockk(),
                sourceManager = mockk { every { getOrStub(42) } returns source },
                downloadManager = mockk(),
                downloadCache = mockk {
                    every { this@mockk.changes } returns changes
                    every { checkLegacyDownloads(any()) } returns true
                    every { getCachedDownloadCount(any()) } answers { counts.value[firstArg<Manga>().id] ?: 0 }
                },
                trackerManager = mockk { every { loggedInTrackersFlow() } returns flowOf(emptyList()) },
                getTracks = mockk(),
            )

            try {
                withTimeout(10_000) {
                    val unfiltered = viewModel.state.first { !it.isLoading }
                    assertEquals(3, unfiltered.getLibraryItemsByPage(0).size)

                    filter.set(TriState.ENABLED_IS)
                    val filtered = viewModel.state.first { it.hasActiveFilters }
                    assertTrue(filtered.checkingDownloads)
                    assertEquals(setOf(1L), filtered.getLibraryItemsByPage(0).map { it.id }.toSet())

                    counts.value = mapOf(1L to 1, 2L to 1)
                    changes.emit(Unit)
                    val updated = viewModel.state.first { it.getLibraryItemsByPage(0).size == 2 }
                    assertTrue(updated.checkingDownloads)
                    assertEquals(setOf(1L, 2L), updated.getLibraryItemsByPage(0).map { it.id }.toSet())
                }
            } finally {
                viewModel.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private inline fun <reified T : Any> preference(initialValue: T): Preference<T> {
        val value = MutableStateFlow(initialValue)
        return mockk {
            every { get() } answers { value.value }
            every { changes() } returns value
            every { set(any()) } answers { value.value = firstArg() }
        }
    }
}
