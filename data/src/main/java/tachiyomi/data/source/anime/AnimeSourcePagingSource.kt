package tachiyomi.data.source.anime

import androidx.paging.PagingState
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.anime.repository.AnimeSourcePagingSourceType
import kiroha.core.PageDeduplicator

class AnimeSourceSearchPagingSource(
    source: AnimeSource,
    val query: String,
    val filters: AnimeFilterList,
) : AnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getSearchAnime(currentPage, query, filters)
    }
}

class AnimeSourcePopularPagingSource(source: AnimeSource) : AnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getPopularAnime(currentPage)
    }
}

class AnimeSourceLatestPagingSource(source: AnimeSource) : AnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class AnimeSourcePagingSource(
    protected val source: AnimeSource,
) : AnimeSourcePagingSourceType() {

    private val pageDeduplicator = PageDeduplicator<SAnime>(canonicalKey = { it.url })

    abstract suspend fun requestNextPage(currentPage: Int): AnimesPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, SAnime> {
        var page = params.key ?: 1
        // Some extensions yield empty or entirely repeated intermediate pages.
        // Walk forward within one Paging load so Paging does not stall on an empty append.
        repeat(MAX_SKIPPED_PAGES_PER_LOAD) {
            val animesPage = try {
                withIOContext { requestNextPage(page.toInt()) }
            } catch (e: Exception) {
                return LoadResult.Error(e)
            }
            val result = pageDeduplicator.accept(animesPage.animes, animesPage.hasNextPage)
            if (result.items.isNotEmpty() || !result.hasNext) {
                return LoadResult.Page(
                    data = result.items,
                    prevKey = null,
                    nextKey = if (result.hasNext) page + 1 else null,
                )
            }
            page++
        }
        // The deduplicator's bounded loop guard avoids requesting infinite
        // copies from broken extensions. Do not spin forever on empty pages.
        return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
    }

    private companion object {
        const val MAX_SKIPPED_PAGES_PER_LOAD = 6
    }

    override fun getRefreshKey(state: PagingState<Long, SAnime>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}
