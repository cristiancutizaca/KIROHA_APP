package eu.kanade.tachiyomi.ui.browse.manga.source.browse

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.asState
import eu.kanade.domain.entries.manga.interactor.UpdateManga
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.manga.interactor.AddMangaTracks
import eu.kanade.tachiyomi.data.cache.MangaCoverCache
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.category.manga.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.toMangaUpdate
import tachiyomi.domain.items.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.manga.interactor.GetRemoteManga
import tachiyomi.domain.source.manga.service.MangaSourceManager
import kotlin.time.Clock
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

@AssistedInject
class BrowseMangaSourceViewModel(
    @Assisted private val sourceId: Long,
    @Assisted listingQuery: String?,
    sourceManager: MangaSourceManager,
    sourcePreferences: SourcePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val coverCache: MangaCoverCache,
    private val getRemoteManga: GetRemoteManga,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getCategories: GetMangaCategories,
    private val setMangaCategories: SetMangaCategories,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val getManga: GetManga,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateManga: UpdateManga,
    private val addTracks: AddMangaTracks,
    getIncognitoState: GetMangaIncognitoState,
) : ViewModel() {

    val state: StateFlow<BrowseMangaSourceViewModel.State>
        field = MutableStateFlow<BrowseMangaSourceViewModel.State>(State(Listing.valueOf(listingQuery)))

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: Long, listingQuery: String?): BrowseMangaSourceViewModel
    }

    var displayMode by sourcePreferences.sourceDisplayMode.asState(viewModelScope)

    val source = sourceManager.getOrStub(sourceId)

    init {
        if (source is CatalogueSource) {
            state.update {
                var query: String? = null
                var listing = it.listing

                if (listing is Listing.Search) {
                    query = listing.query
                    listing = Listing.Search(query, source.getFilterList())
                }

                it.copy(
                    listing = listing,
                    filters = source.getFilterList(),
                    toolbarQuery = query,
                )
            }
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource.set(source.id)
        }
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    val mangaPagerFlow = state.map { it.listing to it.searchGeneration }
        .distinctUntilChanged()
        .flatMapLatest { (listing, _) ->
            // Each new search owns its row subscriptions. The previous version
            // launched one stateIn job per item directly in viewModelScope, so
            // repeated searches retained thousands of idle jobs until exit.
            val listingScope = CoroutineScope(
                viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]),
            )
            Pager(PagingConfig(pageSize = 25, prefetchDistance = 12, initialLoadSize = 25)) {
                getRemoteManga.subscribe(sourceId, listing.query ?: "", listing.filters)
            }.flow.map { pagingData ->
                pagingData.map {
                    networkToLocalManga.await(it.toDomainManga(sourceId))
                        .let { localManga ->
                            getManga.subscribe(localManga.url, localManga.source)
                                .filterNotNull()
                                // Do not permanently collect a StateFlow for every browsed item.
                                .stateIn(listingScope, SharingStarted.WhileSubscribed(5_000), localManga)
                        }
                }
                    .filter { !hideInLibraryItems || !it.value.favorite }
            }.onCompletion { listingScope.cancel() }
        }
        // Cache only the active listing generation; previously each new listing
        // created an independently cached flow retained for the ViewModel lifetime.
        .cachedIn(viewModelScope)

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.mangaLandscapeColumns
        } else {
            libraryPreferences.mangaPortraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    // returns the number from the size slider
    fun getColumnsPreferenceForCurrentOrientation(orientation: Int): Int {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) {
            libraryPreferences.mangaLandscapeColumns
        } else {
            libraryPreferences.mangaPortraitColumns
        }.get()
    }

    fun resetFilters() {
        if (source !is CatalogueSource) return

        state.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        state.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        if (source !is CatalogueSource) return

        state.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        if (source !is CatalogueSource) return

        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source.getFilterList())

        state.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
                // Re-run searches even when the same text and filters are submitted.
                searchGeneration = it.searchGeneration + 1,
            )
        }
    }

    fun searchGenre(genreName: String) {
        if (source !is CatalogueSource) return

        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        state.update {
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
            )
        }
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    // Keep a single observable category snapshot instead of opening a new DB flow on
    // every favorite tap. Category changes automatically invalidate the cached list.
    private val cachedUserCategories = getCategories.subscribe()
        .map { all -> all.filterNot { it.isSystemCategory } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private suspend fun updateMangaFavorite(manga: Manga) {
        var new = manga.copy(
            favorite = !manga.favorite,
            dateAdded = if (manga.favorite) 0 else Clock.System.now().toEpochMilliseconds(),
        )
        if (!new.favorite) {
            new = new.removeCovers(coverCache)
        } else {
            setMangaDefaultChapterFlags.await(manga)
        }
        // Persist library membership before optional tracker/network integration.
        updateManga.await(new.toMangaUpdate())
        if (new.favorite) {
            try {
                addTracks.bindEnhancedTrackers(manga, source)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("KirohaFavorites", "Optional tracker binding failed", error)
            }
        }
    }

    fun changeMangaFavorite(manga: Manga) {
        viewModelScope.launchIO { updateMangaFavorite(manga) }
    }

    fun addFavorite(manga: Manga) {
        viewModelScope.launchIO {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val selected = categories.find { it.id == defaultCategoryId.toLong() }
            when {
                selected != null || defaultCategoryId == 0 || categories.isEmpty() -> {
                    // Commit the category assignment before publishing the favorite update.
                    // Two independent launched coroutines previously raced on every addition.
                    setMangaCategories.await(
                        mangaId = manga.id,
                        categoryIds = selected?.let { listOf(it.id) }.orEmpty(),
                    )
                    updateMangaFavorite(manga)
                }
                else -> {
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                    setDialog(
                        Dialog.ChangeMangaCategory(
                            manga,
                            categories.mapAsCheckboxState { it.id in preselectedIds },
                        ),
                    )
                }
            }
        }
    }

    /** Cached for rapid repeated additions; fall back to DB while first snapshot loads. */
    suspend fun getCategories(): List<Category> {
        return cachedUserCategories.value.takeIf { it.isNotEmpty() }
            ?: getCategories.await().filterNot { it.isSystemCategory }
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): Manga? {
        return getDuplicateLibraryManga.await(manga).getOrNull(0)
    }

    private fun moveMangaToCategories(manga: Manga, vararg categories: Category) {
        moveMangaToCategories(manga, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setMangaCategories.await(
                mangaId = manga.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        state.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        state.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class Search(override val query: String?, override val filters: FilterList) : Listing(
            query = query,
            filters = filters,
        )

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(val manga: Manga, val duplicate: Manga) : Dialog
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val newManga: Manga, val oldManga: Manga) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val searchGeneration: Long = 0,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }
}
