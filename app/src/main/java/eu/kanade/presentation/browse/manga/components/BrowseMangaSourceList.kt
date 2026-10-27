package eu.kanade.presentation.browse.manga.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.BrowseSourceLoadingItem
import eu.kanade.presentation.browse.InLibraryBadge
import eu.kanade.presentation.library.components.CommonEntryItemDefaults
import eu.kanade.presentation.library.components.EntryListItem
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.MangaCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseMangaSourceList(
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    entries: Int,
    topBarHeight: Int,
    contentPadding: PaddingValues,
    displayOrder: List<Int>? = null,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    // Older LazyColumn positions may be queried as the new search replaces paging data.
    val keySnapshot = mangaList.itemSnapshotList
    val snapshotCount = keySnapshot.size
    val sourceListState = rememberLazyListState()
    BoxWithConstraints {
        val density = LocalDensity.current
        val containerHeightPx = with(density) { this@BoxWithConstraints.maxHeight.roundToPx() }

        LazyColumn(
            state = sourceListState,
            contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
        ) {
            item {
                if (mangaList.loadState.prepend is LoadState.Loading) {
                    BrowseSourceLoadingItem()
                }
            }

            items(
                count = snapshotCount,
                key = { index ->
                    val sourceIndex = displayOrder?.getOrNull(index) ?: index
                    keySnapshot.getOrNull(sourceIndex)?.value?.let { "${it.source}:${it.url}" }
                        ?: "manga-placeholder-$sourceIndex"
                },
            ) { index ->
                val sourceIndex = displayOrder?.getOrNull(index) ?: index
                val currentCount = mangaList.itemCount
                if (sourceIndex !in 0 until currentCount) return@items
                // Ordered browsing must still prefetch the paging tail.
                if (displayOrder != null && index >= currentCount - 12) {
                    mangaList[currentCount - 1]
                }
                val manga by mangaList[sourceIndex]?.collectAsState() ?: return@items
                BrowseMangaSourceListItem(
                    manga = manga,
                    onClick = { onMangaClick(manga) },
                    onLongClick = { onMangaLongClick(manga) },
                    entries = entries,
                    containerHeight = containerHeightPx - topBarHeight,
                )
            }

            item {
                if (mangaList.loadState.refresh is LoadState.Loading ||
                    mangaList.loadState.append is LoadState.Loading
                ) {
                    BrowseSourceLoadingItem()
                }
            }
        }
    }
}

@Composable
private fun BrowseMangaSourceListItem(
    manga: Manga,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    entries: Int,
    containerHeight: Int,
) {
    EntryListItem(
        title = manga.title,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverAlpha = if (manga.favorite) CommonEntryItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        badge = {
            InLibraryBadge(enabled = manga.favorite)
        },
        onLongClick = onLongClick,
        onClick = onClick,
        entries = entries,
        containerHeight = containerHeight,
    )
}
