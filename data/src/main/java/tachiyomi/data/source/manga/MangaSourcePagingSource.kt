package tachiyomi.data.source.manga

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.manga.repository.SourcePagingSourceType
import kiroha.core.PageDeduplicator

class SourceSearchPagingSource(
    source: CatalogueSource,
    val query: String,
    val filters: FilterList,
) :
    SourcePagingSource(
        source,
    ) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getSearchManga(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(source: CatalogueSource) : SourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(source: CatalogueSource) : SourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class SourcePagingSource(
    protected val source: CatalogueSource,
) : SourcePagingSourceType() {

    private val pageDeduplicator = PageDeduplicator<SManga>(canonicalKey = { it.url })

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, SManga> {
        var page = params.key ?: 1
        // Some extensions yield empty or entirely repeated intermediate pages.
        // Walk forward within one Paging load so Paging does not stall on an empty append.
        repeat(MAX_SKIPPED_PAGES_PER_LOAD) {
            val mangasPage = try {
                withIOContext { requestNextPage(page.toInt()) }
            } catch (e: Exception) {
                return LoadResult.Error(e)
            }
            val result = pageDeduplicator.accept(mangasPage.mangas, mangasPage.hasNextPage)
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

    override fun getRefreshKey(state: PagingState<Long, SManga>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}
