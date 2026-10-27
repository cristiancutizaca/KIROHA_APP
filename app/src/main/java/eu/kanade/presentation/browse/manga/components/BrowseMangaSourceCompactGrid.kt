package eu.kanade.presentation.browse.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.BrowseSourceLoadingItem
import eu.kanade.presentation.browse.InLibraryBadge
import eu.kanade.presentation.library.components.CommonEntryItemDefaults
import eu.kanade.presentation.library.components.EntryCompactGridItem
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.MangaCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseMangaSourceCompactGrid(
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    displayOrder: List<Int>? = null,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    // Compose may still request old item keys while a new search replaces the paging data.
    // Keep keys and the item count bound to the same immutable snapshot.
    val keySnapshot = mangaList.itemSnapshotList
    val snapshotCount = keySnapshot.size
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonEntryItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonEntryItemDefaults.GridHorizontalSpacer),
    ) {
        if (mangaList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
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
            // This composition may outlive its paging generation for a frame.
            // Never index the next search's shorter list with an old layout position.
            val currentCount = mangaList.itemCount
            if (sourceIndex !in 0 until currentCount) return@items
            // When visually reordered, still prefetch the loaded paging tail.
            if (displayOrder != null && index >= currentCount - 12) {
                mangaList[currentCount - 1]
            }
            val manga by mangaList[sourceIndex]?.collectAsState() ?: return@items
            BrowseMangaSourceCompactGridItem(
                manga = manga,
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
            )
        }

        if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseMangaSourceCompactGridItem(
    manga: Manga,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    EntryCompactGridItem(
        title = manga.title,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverAlpha = if (manga.favorite) CommonEntryItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        coverBadgeStart = {
            InLibraryBadge(enabled = manga.favorite)
        },
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
