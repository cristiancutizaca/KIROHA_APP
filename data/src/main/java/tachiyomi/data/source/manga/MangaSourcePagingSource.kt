package tachiyomi.data.source.manga

import android.util.Log
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
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
        var page = params.key ?: 1L
        var emptyPages = 0

        try {
            while (true) {
                val networkStart = System.nanoTime()
                val response = withIOContext { requestNextPage(page.toInt()) }
                val networkMs = (System.nanoTime() - networkStart) / 1_000_000
                val dedupStart = System.nanoTime()
                val filtered = pageDeduplicator.accept(response.mangas, response.hasNextPage)
                val dedupMs = (System.nanoTime() - dedupStart) / 1_000_000

                Log.d(
                    "KirohaPaging",
                    "type=manga source=${source.id} page=$page " +
                        "received=${response.mangas.size} unique=${filtered.items.size} " +
                        "duplicates=${filtered.duplicatesRemoved} next=${response.hasNextPage} " +
                        "networkMs=$networkMs dedupMs=$dedupMs",
                )

                if (filtered.items.isNotEmpty() || !response.hasNextPage) {
                    return LoadResult.Page(
                        data = filtered.items,
                        prevKey = null,
                        nextKey = if (response.hasNextPage) page + 1 else null,
                    )
                }

                // An extension may return duplicate-only or empty intermediate pages.
                // Skip forward inside this load instead of giving Paging an empty result.
                // Report a recoverable error if a broken source never makes progress.
                emptyPages++
                if (emptyPages >= MAX_CONSECUTIVE_EMPTY_SOURCE_PAGES) {
                    return LoadResult.Error(
                        IllegalStateException(
                            "manga source ${source.id} returned $emptyPages pages without new items",
                        ),
                    )
                }
                page++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LoadResult.Error(e)
        }
    }

    private companion object {
        const val MAX_CONSECUTIVE_EMPTY_SOURCE_PAGES = 12
    }

    override fun getRefreshKey(state: PagingState<Long, SManga>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}
