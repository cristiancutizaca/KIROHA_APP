package mihon.feature.library

import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mihon.domain.library.model.search.AndNode
import mihon.domain.library.model.search.ComparisonField
import mihon.domain.library.model.search.ComparisonQueryNode
import mihon.domain.library.model.search.EmptyQueryNode
import mihon.domain.library.model.search.FieldQueryNode
import mihon.domain.library.model.search.GeneralQueryNode
import mihon.domain.library.model.search.LibraryMetadataTags
import mihon.domain.library.model.search.MangaField
import mihon.domain.library.model.search.NotNode
import mihon.domain.library.model.search.OrNode
import mihon.domain.library.model.search.QueryNode
import tachiyomi.source.local.LocalSource
import kotlin.math.abs
import kotlin.time.Instant

fun QueryNode.matches(item: MangaLibraryItem): Boolean {
    return when (this) {
        is AndNode -> children.all { it.matches(item) }
        is OrNode -> children.any { it.matches(item) }
        is NotNode -> !child.matches(item)
        is EmptyQueryNode -> true
        is GeneralQueryNode -> matches(item)
        is FieldQueryNode -> matches(item)
        is ComparisonQueryNode -> matches(item)
    }
}

/**
 * Plain library queries also examine original source metadata. Gallery extensions
 * frequently put parody/character tags in the description instead of the title
 * or the genre list, and custom information can hide the source description.
 */
private fun GeneralQueryNode.matches(item: MangaLibraryItem): Boolean {
    val manga = item.libraryManga.manga

    // Use when so each added field has to be handled explicitly.
    val match = MangaField.entries.any { field ->
        if (field.fieldOnly) return@any false

        when (field) {
            MangaField.TITLE -> matchesText(value, manga.title, manga.ogTitle)

            MangaField.AUTHOR -> matchesText(value, manga.author, manga.ogAuthor)

            MangaField.ARTIST -> matchesText(value, manga.artist, manga.ogArtist)

            MangaField.DESCRIPTION -> matchesText(value, manga.description, manga.ogDescription)

            MangaField.GENRE ->
                manga.genre.orEmpty().any { it.contains(value, ignoreCase = true) } ||
                    manga.ogGenre.orEmpty().any { it.contains(value, ignoreCase = true) }

            MangaField.SOURCE -> {
                item.sourceName.contains(value, ignoreCase = true) ||
                    (value.equals("local", ignoreCase = true) && manga.source == LocalSource.ID)
            }

            MangaField.NOTES -> manga.notes.contains(value, ignoreCase = true)

            // These require field queries or are handled by the source metadata above.
            MangaField.TAG,
            MangaField.PARODY,
            MangaField.CHARACTER,
            MangaField.FEMALE,
            MangaField.MALE,
            MangaField.MIXED,
            MangaField.OTHER,
            MangaField.GROUP,
            MangaField.COSPLAYER,
            MangaField.UPLOADER,
            MangaField.LANGUAGE,
            MangaField.SOURCE_ID,
            -> error("How did we get here?")
        }
    }
    return if (negated) !match else match
}

private fun FieldQueryNode.matches(item: MangaLibraryItem): Boolean {
    val manga = item.libraryManga.manga

    fun matchesMetadata(category: String): Boolean = LibraryMetadataTags.matches(
        value = value,
        category = category,
        genres = manga.genre.orEmpty() + manga.ogGenre.orEmpty(),
        descriptions = listOf(manga.description, manga.ogDescription),
    )

    val match = when (field) {
        MangaField.GENRE -> {
            if (value.isEmpty()) {
                manga.genre.isNullOrEmpty() && manga.ogGenre.isNullOrEmpty()
            } else {
                matchesMetadata("genre")
            }
        }

        MangaField.TAG -> matchesMetadata("tag")
        MangaField.PARODY -> matchesMetadata("parody")
        MangaField.CHARACTER -> matchesMetadata("character")
        MangaField.FEMALE -> matchesMetadata("female")
        MangaField.MALE -> matchesMetadata("male")
        MangaField.MIXED -> matchesMetadata("mixed")
        MangaField.OTHER -> matchesMetadata("other")
        MangaField.GROUP -> matchesMetadata("group")
        MangaField.COSPLAYER -> matchesMetadata("cosplayer")
        MangaField.UPLOADER -> matchesMetadata("uploader")

        MangaField.SOURCE -> {
            if (value.isEmpty()) {
                item.sourceName.isEmpty()
            } else {
                item.sourceName.contains(value, ignoreCase = true) ||
                    (value.equals("local", ignoreCase = true) && manga.source == LocalSource.ID)
            }
        }

        MangaField.SOURCE_ID -> {
            value.toLongOrNull()?.let { it == manga.source } ?: false
        }

        MangaField.TITLE -> matchesText(value, manga.title, manga.ogTitle)
        MangaField.AUTHOR -> matchesText(value, manga.author, manga.ogAuthor)
        MangaField.ARTIST ->
            matchesText(value, manga.artist, manga.ogArtist) ||
                matchesMetadata("artist")

        MangaField.DESCRIPTION -> matchesText(value, manga.description, manga.ogDescription)
        MangaField.NOTES -> matchesText(value, manga.notes)
        MangaField.LANGUAGE ->
            matchesText(value, item.sourceLanguage) ||
                matchesMetadata("language")
    }

    return if (negated) !match else match
}

private fun matchesText(value: String, vararg texts: String?): Boolean {
    return if (value.isEmpty()) {
        texts.all { it.isNullOrEmpty() }
    } else {
        texts.any { it?.contains(value, ignoreCase = true) == true }
    }
}

private fun ComparisonQueryNode.matches(item: MangaLibraryItem): Boolean {
    val manga = item.libraryManga.manga

    fun compareDates(timestamp: Long, value: String): Boolean? {
        val inputDate = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
        val mangaDate = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault()).date
        return queryComparator.apply(mangaDate, inputDate)
    }

    val match = when (field) {
        ComparisonField.ID -> value.toLongOrNull()?.let { queryComparator.apply(manga.id, it) }

        ComparisonField.DATE_ADDED -> compareDates(manga.dateAdded, value)

        ComparisonField.FETCH_INTERVAL -> value.toIntOrNull()
            ?.let { queryComparator.apply(abs(manga.fetchInterval), it) }

        ComparisonField.NEXT_UPDATE -> compareDates(manga.nextUpdate, value)

        ComparisonField.UNREAD -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.unreadCount, it)
            }
        }

        ComparisonField.READ -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.libraryManga.readCount, it)
            }
        }

        ComparisonField.TOTAL -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.libraryManga.totalChapters, it)
            }
        }
    } ?: false

    return if (negated) !match else match
}
