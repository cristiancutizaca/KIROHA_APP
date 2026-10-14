package mihon.domain.library.model.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryMetadataTagsTest {

    private val galleryDetails = """
        Title: A completely unrelated gallery
        Uploader: 284615a
        Tags:
          • parody: <Grand Blue> <ArkNights Endfield>
          • character: <Endministrator>
          • female: <pantyhose>
          • other: <ai generated>
    """.trimIndent()

    @Test
    fun findsSeriesByParodyEvenIfTitleIsUnrelated() {
        assertTrue(LibraryMetadataTags.matches("grand blue", descriptions = listOf(galleryDetails)))
        assertTrue(
            LibraryMetadataTags.matches(
                value = "Grand Blue",
                category = "parody",
                descriptions = listOf(galleryDetails),
            ),
        )
        assertTrue(
            LibraryMetadataTags.matches(
                value = "grand blue",
                category = "tag",
                descriptions = listOf(galleryDetails),
            ),
        )
    }

    @Test
    fun scopedSearchesDoNotConfuseParodyAndCharacter() {
        assertTrue(
            LibraryMetadataTags.matches("Endministrator", "character", descriptions = listOf(galleryDetails)),
        )
        assertFalse(
            LibraryMetadataTags.matches("Grand Blue", "character", descriptions = listOf(galleryDetails)),
        )
        assertFalse(
            LibraryMetadataTags.matches("Endministrator", "parody", descriptions = listOf(galleryDetails)),
        )
    }

    @Test
    fun searchesGenreEntriesEvenIfDescriptionIsEmpty() {
        assertTrue(
            LibraryMetadataTags.matches(
                value = "grand blue",
                category = "tag",
                genres = listOf("Grand Blue", "female:pantyhose"),
            ),
        )
        assertTrue(
            LibraryMetadataTags.matches(
                value = "PANTYHOSE",
                category = "female",
                genres = listOf("female:pantyhose"),
            ),
        )
        assertFalse(
            LibraryMetadataTags.matches(
                value = "grand blue",
                category = "parody",
                genres = listOf("Grand Blue"),
            ),
        )
    }

    @Test
    fun tagQueryDoesNotMatchUploaderMetadata() {
        assertFalse(LibraryMetadataTags.matches("284615a", category = "tag", descriptions = listOf(galleryDetails)))
        assertTrue(LibraryMetadataTags.matches("284615a", category = "uploader", descriptions = listOf(galleryDetails)))
        assertFalse(LibraryMetadataTags.matches("anything", descriptions = listOf(null, "")))
    }

    @Test
    fun supportsOriginalSourceDescriptionAlongsideCustomDescription() {
        assertTrue(
            LibraryMetadataTags.matches(
                value = "arknights endfield",
                category = "parody",
                descriptions = listOf("Edited summary without tags", galleryDetails),
            ),
        )
    }

    @Test
    fun parsesTypedMultiwordLibrarySearches() {
        val query = QueryNode.from("""parody:"grand blue" character:Endministrator""")
        assertEquals(
            AndNode(
                listOf(
                    FieldQueryNode(MangaField.PARODY, "grand blue", false),
                    FieldQueryNode(MangaField.CHARACTER, "Endministrator", false),
                ),
            ),
            query,
        )
    }
}
