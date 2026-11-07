package tachiyomi.data.source.manga

import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.manga.repository.SourcePagingSourceType

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

/**
 * Mihon-compatible, forward-only source paging.
 *
 * Pages and ordering come directly from the installed extension. Do not sort,
 * skip, or reinterpret the extension's result pages in the reader. This makes
 * searches, popular titles, and latest updates behave like Mihon's browse.
 */
abstract class SourcePagingSource(
    protected val source: CatalogueSource,
) : SourcePagingSourceType() {

    private val seenManga = hashSetOf<String>()

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, SManga> {
        val page = params.key ?: 1L

        return try {
            val response = withIOContext {
                requestNextPage(page.toInt())
                    .takeIf { it.mangas.isNotEmpty() }
                    ?: throw MangaSourceNoResultsException()
            }

            // Keep the exact order returned by the extension, de-duplicating
            // by its URL just as Mihon does.
            val manga = response.mangas.filter { seenManga.add(it.url) }

            LoadResult.Page(
                data = manga,
                prevKey = null,
                nextKey = if (response.hasNextPage) page + 1 else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, SManga>): Long? {
        // Rebuild from page 1. As this source is forward-only, using the next
        // page as the refresh anchor silently drops earlier titles.
        return null
    }
}

class MangaSourceNoResultsException : Exception()
