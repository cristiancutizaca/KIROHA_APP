package tachiyomi.data.source.anime

import android.util.Log
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
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
        var page = params.key ?: 1L
        var emptyPages = 0

        try {
            while (true) {
                val networkStart = System.nanoTime()
                val response = withIOContext { requestNextPage(page.toInt()) }
                val networkMs = (System.nanoTime() - networkStart) / 1_000_000
                val dedupStart = System.nanoTime()
                val filtered = pageDeduplicator.accept(response.animes, response.hasNextPage)
                val dedupMs = (System.nanoTime() - dedupStart) / 1_000_000

                Log.d(
                    "KirohaPaging",
                    "type=anime source=${source.id} page=$page " +
                        "received=${response.animes.size} unique=${filtered.items.size} " +
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
                            "anime source ${source.id} returned $emptyPages pages without new items",
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

    override fun getRefreshKey(state: PagingState<Long, SAnime>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}
