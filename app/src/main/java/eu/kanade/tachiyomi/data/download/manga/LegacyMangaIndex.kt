package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.manga.model.Manga
import java.io.File
import java.io.FileNotFoundException
import kotlin.time.Duration.Companion.hours

/** Stores verified legacy matches in app-private storage for the configured download directory. */
internal class LegacyMangaIndex(private val context: Context) {
    private val file = AtomicFile(File(context.cacheDir, "legacy_manga_index_v1"))

    fun read(rootUri: String): Map<Key, Record> {
        return try {
            val snapshot = file.openRead().use { ProtoBuf.decodeFromByteArray<Snapshot>(it.readBytes()) }
            if (snapshot.rootUri == rootUri) snapshot.records else emptyMap()
        } catch (_: FileNotFoundException) {
            emptyMap()
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Could not load the legacy manga download index" }
            emptyMap()
        }
    }

    fun write(rootUri: String, records: Map<Key, Record>) {
        try {
            val bytes = ProtoBuf.encodeToByteArray(Snapshot(rootUri, records))
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (failure: Throwable) {
                file.failWrite(stream)
                throw failure
            }
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Could not save the legacy manga download index" }
        }
    }

    @Serializable
    data class Snapshot(val rootUri: String, val records: Map<Key, Record>)

    @Serializable
    data class Key(val sourceId: Long, val mangaUrl: String) {
        constructor(manga: Manga) : this(manga.source, manga.url)
    }

    @Serializable
    data class LibraryEntry(
        val title: String,
        val originalTitle: String,
        val chapterCount: Long,
        val chapterFetchedAt: Long,
    )

    @Serializable
    data class Folder(val lastModified: Long, val mayMatchByTitle: Boolean)

    @Serializable
    data class Record(
        val libraryEntry: LibraryEntry,
        val folders: Map<String, Folder>,
        val verifiedAt: Long,
        val chapterUrls: Set<String>,
        val files: Map<String, LegacyMangaDownloads.Download>,
        val unmatchedCount: Int,
    ) {
        fun canReuse(entry: LibraryEntry, currentFolders: Map<String, Folder>, now: Long): Boolean {
            return libraryEntry == entry && folders == currentFolders &&
                now - verifiedAt in 0..RECHECK_INTERVAL.inWholeMilliseconds
        }

        fun restoreDownloads(): LegacyMangaDownloads = LegacyMangaDownloads(chapterUrls, files, unmatchedCount)

        companion object {
            private val RECHECK_INTERVAL = 24.hours

            fun from(
                libraryEntry: LibraryEntry,
                folders: Map<String, Folder>,
                verifiedAt: Long,
                downloads: LegacyMangaDownloads,
            ): Record {
                return Record(
                    libraryEntry,
                    folders,
                    verifiedAt,
                    downloads.chapterUrls,
                    downloads.files,
                    downloads.unmatchedCount,
                )
            }
        }
    }
}
