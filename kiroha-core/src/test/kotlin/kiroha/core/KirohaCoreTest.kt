package kiroha.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class KirohaCoreTest {
    @Test
    fun `device id survives reload and lastSeen is refreshed`() {
        val store = InMemoryKeyValueStore()
        var now = 100L
        val registry = DeviceRegistry(store, { now }, { "device-a" })
        val first = registry.getOrCreate("Pixel", "0.1")
        now = 200L
        val second = DeviceRegistry(store, { now }, { "unused" }).getOrCreate("Pixel renamed", "0.2")
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(100L, second.createdAt)
        assertEquals(200L, second.lastSeen)
        assertEquals("0.2", second.appVersion)
    }

    @Test
    fun `global download remains true for an offline remote copy`() {
        val records = listOf(DownloadRecord("x", "phone-b", "sd-1", DownloadAvailability.LAST_KNOWN_REMOTE, downloadedEver = true))
        assertTrue(DownloadStateResolver.isDownloadedGlobally(records, "x"))
        assertFalse(DownloadStateResolver.isAvailableHere(records, "x", "phone-a"))
        assertTrue(DownloadStateResolver.downloadedEver(records, "x"))
    }

    @Test
    fun `deleted copy keeps downloadedEver but no longer counts as downloaded`() {
        val record = DownloadRecord("x", "phone-a", "internal", DownloadAvailability.DELETED, downloadedEver = true)
        assertFalse(DownloadStateResolver.isDownloadedGlobally(listOf(record), "x"))
        assertTrue(DownloadStateResolver.downloadedEver(listOf(record), "x"))
    }

    @Test
    fun `physical deletion transition records timestamp and preserves history`() {
        val record = DownloadRecord("x", "phone-a", "internal", DownloadAvailability.AVAILABLE_LOCAL, false)
        val deleted = DownloadStateResolver.markDeleted(record, 99L)
        assertEquals(DownloadAvailability.DELETED, deleted.state)
        assertTrue(deleted.downloadedEver)
        assertEquals(99L, deleted.deletedAt)
    }

    @Test
    fun `canonical progress resolves different local chapter ids`() {
        data class LocalChapter(val id: Long, val url: String)
        val target = CanonicalProgressResolver.resolve(
            sourceNamespace = "42",
            canonicalId = "chapter-url-x",
            localItems = listOf(LocalChapter(9000, "chapter-url-x")),
            canonical = { it.url },
            localId = { it.id },
        )
        assertEquals(9000L, target?.localId)
        assertEquals("42", target?.sourceNamespace)
        assertNull(CanonicalProgressResolver.resolve("42", "missing", listOf(LocalChapter(100, "x")), { it.url }, { it.id }))
    }

    @Test
    fun `same selected storage identity does not duplicate internal and saf aliases`() {
        assertEquals(KirohaIdentity.storage("content://tree/primary", saf = true), KirohaIdentity.storage("content://tree/primary", saf = true))
        assertNotEquals(KirohaIdentity.storage("content://tree/primary", saf = true), KirohaIdentity.storage("/storage/primary", saf = false))
    }

    @Test
    fun `remote download is global but not local and never imports remote path`() {
        val records = DownloadSyncReducer.applyRemote(
            records = emptyList(), remoteDeviceId = "m8", remoteStorageLabel = "sd-card",
            itemId = "manga:42:chapter-x", remoteState = DownloadAvailability.AVAILABLE_LOCAL,
            downloadedEver = true, size = 123L, lastVerifiedAt = 77L,
            sourceNamespace = "42", canonicalId = "chapter-x",
        )
        val index = DownloadIndex(records)
        assertTrue(index.isDownloaded("manga:42:chapter-x"))
        assertFalse(index.isAvailableHere("manga:42:chapter-x", "main"))
        assertTrue(index.isAvailableRemotely("manga:42:chapter-x", "main"))
        assertNull(records.single().localPath)
    }

    @Test
    fun `deleting one of two copies keeps global download until last copy disappears`() {
        val b = DownloadRecord("x", "b", "sd", DownloadAvailability.AVAILABLE_REMOTE, true)
        val c = DownloadRecord("x", "c", "internal", DownloadAvailability.AVAILABLE_REMOTE, true)
        val afterB = DownloadIndex(listOf(DownloadStateResolver.markDeleted(b, 1L), c))
        assertTrue(afterB.isDownloaded("x"))
        val afterC = DownloadIndex(listOf(DownloadStateResolver.markDeleted(b, 1L), DownloadStateResolver.markDeleted(c, 2L)))
        assertFalse(afterC.isDownloaded("x"))
        assertTrue(afterC.isHistory("x"))
    }

    @Test
    fun `pagination keeps distinct canonical URLs across pages`() {
        val deduplicator = PageDeduplicator<String>(canonicalKey = { it })
        val first = deduplicator.accept(listOf("/entry/1", "/entry/2", "/entry/3"), true)
        val second = deduplicator.accept(listOf("/entry/3", "/entry/4", "/entry/5"), true)
        assertEquals(listOf("/entry/1", "/entry/2", "/entry/3"), first.items)
        assertEquals(listOf("/entry/4", "/entry/5"), second.items)
        assertEquals(1, second.duplicatesRemoved)
    }

    @Test
    fun `pagination passes intermediate duplicate-only pages and eventually finds new items`() {
        val deduplicator = PageDeduplicator<String>(canonicalKey = { it })
        deduplicator.accept(listOf("A", "B"), true)
        assertTrue(deduplicator.accept(listOf("A", "B"), true).hasNext)
        assertTrue(deduplicator.accept(emptyList(), true).hasNext)
        val newPage = deduplicator.accept(listOf("C", "D"), true)
        assertEquals(listOf("C", "D"), newPage.items)
        assertTrue(newPage.hasNext)
    }

    @Test
    fun `pagination never claims exhaustion while a source has more pages`() {
        val deduplicator = PageDeduplicator<String>(canonicalKey = { it })
        deduplicator.accept(listOf("A", "B"), true)
        repeat(12) {
            assertTrue(deduplicator.accept(listOf("A", "B"), true).hasNext)
        }
        val lateUnique = deduplicator.accept(listOf("C"), false)
        assertEquals(listOf("C"), lateUnique.items)
        assertFalse(lateUnique.hasNext)
    }

    @Test
    fun `pagination retry does not erase a loaded page as duplicate`() {
        val deduplicator = PageDeduplicator<String>(canonicalKey = { it })
        val first = deduplicator.acceptPage(1L, listOf("2026-A", "2025-B"), true)
        val second = deduplicator.acceptPage(2L, listOf("2025-B", "2024-C"), true)
        val retried = deduplicator.acceptPage(2L, listOf("2025-B", "2024-C"), true)

        assertEquals(listOf("2026-A", "2025-B"), first.items)
        assertEquals(listOf("2024-C"), second.items)
        assertEquals(listOf("2024-C"), retried.items)
    }

    @Test
    fun `pagination retains extension order when scrolling through years`() {
        val deduplicator = PageDeduplicator<String>(canonicalKey = { it })
        val first = deduplicator.acceptPage(1L, listOf("2026-A", "2025-A"), true)
        val second = deduplicator.acceptPage(2L, listOf("2024-A", "2023-A"), true)
        val third = deduplicator.acceptPage(3L, listOf("2023-A", "2022-A"), false)

        assertEquals(
            listOf("2026-A", "2025-A", "2024-A", "2023-A", "2022-A"),
            first.items + second.items + third.items,
        )
        assertFalse(third.hasNext)
    }

    @Test
    fun `pagination new generation clears old seen URLs on page one`() {
        val deduplicator = PageDeduplicator<String>(canonicalKey = { it })
        deduplicator.acceptPage(1L, listOf("2026-A"), true)
        deduplicator.acceptPage(2L, listOf("2025-A"), false)

        val reloaded = deduplicator.acceptPage(1L, listOf("2026-A", "2026-B"), true)
        val appended = deduplicator.acceptPage(2L, listOf("2026-B", "2024-A"), false)

        assertEquals(listOf("2026-A", "2026-B"), reloaded.items)
        assertEquals(listOf("2024-A"), appended.items)
    }

    @Test
    fun `storage unavailable copy stays globally downloaded`() {
        val record = DownloadRecord("x", "phone-b", "sd-1", DownloadAvailability.STORAGE_UNAVAILABLE, downloadedEver = true)
        assertTrue(DownloadStateResolver.isDownloadedGlobally(listOf(record), "x"))
    }

    @Test
    fun `manga chooses furthest page and video chooses newest event`() {
        val mangaA = ProgressEvent("x", "c1", 15, ProgressKind.MANGA_PAGE, 100, "a")
        val mangaB = mangaA.copy(position = 12, updatedAt = 200, deviceId = "b")
        assertEquals(mangaA, ProgressConflictResolver.merge(mangaA, mangaB))
        val videoA = ProgressEvent("x", "e1", 100, ProgressKind.VIDEO_POSITION, 100, "a")
        val videoB = videoA.copy(position = 80, updatedAt = 200, deviceId = "b")
        assertEquals(videoB, ProgressConflictResolver.merge(videoA, videoB))
    }

    @Test
    fun `sync journal round trips payloads and acknowledges only selected events`() {
        val store = InMemoryKeyValueStore()
        val journal = LocalSyncJournal(store) { "event-${journalId++}" }
        val first = journal.append(SyncEvent("", SyncEntity.PROGRESS, "chapter-1", SyncOperation.UPSERT, 1, 10, "a", "page=18\tread=true"))
        val second = journal.append(SyncEvent("", SyncEntity.DOWNLOAD, "chapter-1", SyncOperation.DELETE, 2, 20, "a", "deletedAt=20"))

        assertEquals(listOf(first, second), journal.pending())
        journal.acknowledge(setOf(first.eventId))
        assertEquals(listOf(second), journal.pending())
    }

    @Test
    fun `four devices converge from independent cursors without event loops`() {
        val backend = InMemorySyncBackend()
        val space = "space-1"
        val a = SyncClient(space, "a", backend)
        val b = SyncClient(space, "b", backend)
        val c = SyncClient(space, "c", backend)
        val d = SyncClient(space, "d", backend)
        val download = SyncEvent("download-1", SyncEntity.DOWNLOAD, "history-x", SyncOperation.UPSERT, 1, 10, "b", "state=AVAILABLE_REMOTE")
        val read = SyncEvent("read-1", SyncEntity.PROGRESS, "history-x", SyncOperation.UPSERT, 2, 20, "a", "read=true")

        b.push(listOf(download))
        val receivedByA = mutableListOf<String>()
        val receivedByC = mutableListOf<String>()
        a.pull { receivedByA += it.event.eventId }
        c.pull { receivedByC += it.event.eventId }
        assertEquals(listOf("download-1"), receivedByA)
        assertEquals(listOf("download-1"), receivedByC)

        a.push(listOf(read))
        c.pull { receivedByC += it.event.eventId }
        assertEquals(listOf("download-1", "read-1"), receivedByC)

        val receivedByD = mutableListOf<String>()
        d.pull { receivedByD += it.event.eventId }
        assertEquals(listOf("download-1", "read-1"), receivedByD)
        assertEquals(2L, d.cursor.revision)
        assertEquals(2L, backend.cursor(space, "d"))

        // Re-sending the same event is idempotent and does not create a third revision.
        b.push(listOf(download))
        assertEquals(2L, backend.globalRevision(space))
    }

    @Test
    fun `download index prefers local copy and ignores deleted records`() {
        val remote = DownloadRecord("x", "b", "sd", DownloadAvailability.LAST_KNOWN_REMOTE, true, lastVerifiedAt = 300)
        val local = DownloadRecord("x", "a", "internal", DownloadAvailability.AVAILABLE_LOCAL, true, localPath = "/x", lastVerifiedAt = 100)
        val deleted = DownloadRecord("x", "a", "old", DownloadAvailability.DELETED, true, lastVerifiedAt = 500)
        val index = DownloadIndex(listOf(remote, local, deleted))

        assertEquals(local, index.resolve("x", "a"))
        index.update(local.copy(state = DownloadAvailability.DELETED))
        assertEquals(remote, index.resolve("x", "a"))
    }

    @Test
    fun `internal storage writes and reconciler records bounded fingerprint`() {
        val root = Files.createTempDirectory("kiroha-storage")
        val provider = InternalStorageProvider("internal", root)
        provider.write("series/chapter.bin").use { it.write(ByteArray(100_000) { (it % 251).toByte() }) }
        val initial = DownloadRecord("chapter", "phone-a", "internal", DownloadAvailability.MISSING, true, "series/chapter.bin")
        val reconciled = DownloadReconciler.reconcile(initial, provider, 500L)
        assertEquals(DownloadAvailability.AVAILABLE_LOCAL, reconciled.state)
        assertEquals(100_000L, reconciled.size)
        assertNotNull(reconciled.checksum)
        assertEquals(500L, reconciled.lastVerifiedAt)
    }

    @Test
    fun `reconciler migrates matching legacy checksum instead of marking download corrupt`() {
        val root = Files.createTempDirectory("kiroha-legacy-upgrade")
        val underlying = InternalStorageProvider("internal", root)
        underlying.write("chapters/one.bin").use { it.write(byteArrayOf(1, 2, 3)) }
        val legacy = object : StorageProvider by underlying {
            override fun legacyFingerprint(relativePath: String): String = "old-checksum"
        }
        val oldRecord = DownloadRecord(
            "entry-1", "phone-a", "internal", DownloadAvailability.AVAILABLE_LOCAL,
            true, "chapters/one.bin", checksum = "old-checksum",
        )
        val verified = DownloadReconciler.reconcile(oldRecord, legacy, 100L)
        assertEquals(DownloadAvailability.AVAILABLE_LOCAL, verified.state)
        assertEquals(underlying.fingerprint("chapters/one.bin").value, verified.checksum)
        assertNotEquals("old-checksum", verified.checksum)
    }

    @Test
    fun `reconciler distinguishes unavailable storage from missing file`() {
        val root = Files.createTempDirectory("kiroha-storage")
        val provider = InternalStorageProvider("internal", root)
        val record = DownloadRecord("chapter", "phone-a", "internal", DownloadAvailability.AVAILABLE_LOCAL, true, "missing.bin")
        assertEquals(DownloadAvailability.MISSING, DownloadReconciler.reconcile(record, provider, 10L).state)
        val unavailable = InternalStorageProvider("internal", root.resolve("removed"))
        assertEquals(DownloadAvailability.STORAGE_UNAVAILABLE, DownloadReconciler.reconcile(record, unavailable, 11L).state)
    }

    @Test
    fun `download index answers global local and remote queries without filesystem`() {
        val index = DownloadIndex(listOf(
            DownloadRecord("x", "phone-a", "internal", DownloadAvailability.AVAILABLE_LOCAL, true, size = 10),
            DownloadRecord("x", "phone-b", "sd-1", DownloadAvailability.LAST_KNOWN_REMOTE, true, size = 20),
            DownloadRecord("y", "phone-b", "sd-1", DownloadAvailability.DELETED, true),
        ))
        assertTrue(index.isDownloaded("x"))
        assertTrue(index.isAvailableHere("x", "phone-a"))
        assertTrue(index.isAvailableRemotely("x", "phone-a"))
        assertEquals(30L, index.sizeFor("x"))
        assertFalse(index.isDownloaded("y"))
        assertEquals(3, index.recordsForStorage("sd-1").size)
    }

    @Test
    fun `download filters compose from index without filesystem access`() {
        val index = DownloadIndex(listOf(
            DownloadRecord("local", "a", "internal", DownloadAvailability.AVAILABLE_LOCAL, true),
            DownloadRecord("remote", "b", "sd", DownloadAvailability.LAST_KNOWN_REMOTE, true),
            DownloadRecord("offline", "b", "sd", DownloadAvailability.STORAGE_UNAVAILABLE, true),
            DownloadRecord("history", "b", "sd", DownloadAvailability.DELETED, true),
        ))
        assertTrue(DownloadFilterEngine.matches(index, "local", "a", DownloadFilterQuery(setOf(DownloadFilter.AVAILABLE_HERE))))
        assertTrue(DownloadFilterEngine.matches(index, "remote", "a", DownloadFilterQuery(setOf(DownloadFilter.AVAILABLE_REMOTE))))
        assertTrue(DownloadFilterEngine.matches(index, "offline", "a", DownloadFilterQuery(setOf(DownloadFilter.OFFLINE))))
        assertTrue(DownloadFilterEngine.matches(index, "history", "a", DownloadFilterQuery(setOf(DownloadFilter.DOWNLOADED_EVER))))
        assertFalse(DownloadFilterEngine.matches(index, "history", "a", DownloadFilterQuery(setOf(DownloadFilter.DOWNLOADED))))
        assertTrue(DownloadFilterEngine.matches(index, "local", "a", DownloadFilterQuery(read = true), isRead = true))
        assertFalse(DownloadFilterEngine.matches(index, "local", "a", DownloadFilterQuery(read = true), isRead = false))
    }

    @Test
    fun `portable index round trips and can be rebuilt when corrupted`() {
        val root = Files.createTempDirectory("kiroha-portable")
        val provider = InternalStorageProvider("sd-1", root)
        val index = PortableStorageIndex(provider)
        index.write(listOf(PortableStorageIndex.Entry("chapter-1", "series/chapter", "abc", 42)))
        assertEquals("chapter-1", index.read().single().itemId)
        provider.write(PortableStorageIndex.INDEX_PATH).use { it.write("garbage\n".toByteArray()) }
        assertTrue(index.read().isEmpty())
    }

    @Test
    fun `storage reindexer rebuilds indexed records and preserves unavailable state`() {
        val root = Files.createTempDirectory("kiroha-reindex")
        val provider = InternalStorageProvider("internal", root)
        provider.write("series/chapter").use { it.write("content".toByteArray()) }
        val fingerprint = provider.fingerprint("series/chapter")
        PortableStorageIndex(provider).write(listOf(
            PortableStorageIndex.Entry("new-item", "series/chapter", fingerprint.value, fingerprint.size),
        ))

        val rebuilt = StorageReindexer.reconcile(emptyList(), provider, "phone-a", 700L)
        assertEquals(1, rebuilt.size)
        assertEquals("new-item", rebuilt.single().itemId)
        assertEquals(DownloadAvailability.AVAILABLE_LOCAL, rebuilt.single().state)

        val unavailable = InternalStorageProvider("internal", root.resolve("removed"))
        val offline = StorageReindexer.reconcile(rebuilt, unavailable, "phone-a", 701L)
        assertEquals(DownloadAvailability.STORAGE_UNAVAILABLE, offline.single().state)
        assertTrue(offline.single().downloadedEver)
    }

    @Test
    fun `fingerprint covers empty small large beginning ending and size changes`() {
        val root = Files.createTempDirectory("kiroha-fingerprint")
        val file = root.resolve("sample")
        Files.write(file, ByteArray(0))
        val empty = Fingerprints.forPath(file)
        assertEquals(0L, empty.size)
        Files.write(file, byteArrayOf(1, 2, 3, 4))
        val small = Fingerprints.forPath(file)
        Files.setLastModifiedTime(file, FileTime.fromMillis(1234))
        val changedMetadata = Fingerprints.forPath(file)
        assertNotEquals(small.value, changedMetadata.value)

        val content = ByteArray(200_000) { (it % 251).toByte() }
        Files.write(file, content)
        Files.setLastModifiedTime(file, FileTime.fromMillis(2000))
        val large = Fingerprints.forPath(file)
        content[0] = 99
        Files.write(file, content)
        Files.setLastModifiedTime(file, FileTime.fromMillis(2000))
        val changedStart = Fingerprints.forPath(file)
        assertNotEquals(large.value, changedStart.value)
        content[0] = 99
        content[content.lastIndex] = 77
        Files.write(file, content)
        Files.setLastModifiedTime(file, FileTime.fromMillis(2000))
        val changedEnd = Fingerprints.forPath(file)
        assertNotEquals(changedStart.value, changedEnd.value)
        Files.write(file, content + 0)
        Files.setLastModifiedTime(file, FileTime.fromMillis(2000))
        assertNotEquals(changedEnd.value, Fingerprints.forPath(file).value)
    }

    @Test
    fun `diagnostics export redacts identifiers and records timings`() {
        var now = 100L
        val diagnostics = KirohaDiagnostics { now }
        diagnostics.measure("reader/open") { now = 125L }
        val text = diagnostics.exportText("device-secret-token", "space-secret", 2, mapOf("micro-sd-secret" to "offline"))
        assertTrue("durationMs=25" in text)
        assertFalse("device-secret-token" in text)
        assertFalse("space-secret" in text)
        assertFalse("micro-sd-secret" in text)
        assertTrue("pending=2" in text)
    }

    private companion object {
        var journalId = 0
    }
}
