package kiroha.core

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Storage boundary used by downloads, the index and Reader without filesystem scans on lookup. */
interface StorageProvider {
    val id: String
    val type: StorageType
    val displayName: String
    val isAvailable: Boolean
    fun resolve(relativePath: String): StorageFile?
    fun open(relativePath: String): InputStream
    fun write(relativePath: String): OutputStream
    fun delete(relativePath: String): Boolean
    fun fingerprint(relativePath: String): FileFingerprint

    /** Optional legacy metadata-only fingerprint used only to migrate older records. */
    fun legacyFingerprint(relativePath: String): String? = null
}

enum class StorageType { INTERNAL, SAF_TREE, USB, UNKNOWN }

data class StorageFile(val storageId: String, val relativePath: String, val size: Long, val modifiedAt: Long)
data class FileFingerprint(val value: String, val size: Long, val modifiedAt: Long)

/** Bounded fingerprint: metadata plus up to 64 KiB from the beginning and end of a file. */
object Fingerprints {
    fun forPath(path: Path): FileFingerprint {
        val size = Files.size(path)
        val modified = Files.getLastModifiedTime(path).toMillis()
        return forStreams(size, modified, { Files.newInputStream(path) })
    }

    /** Reads small files once; large files use bounded first/last windows. */
    fun forStreams(size: Long, modified: Long, opener: () -> InputStream): FileFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$size:$modified".toByteArray())
        val window = 64 * 1024L
        if (size <= window * 2) {
            opener().use { input ->
                val bytes = input.readBounded(size.toInt())
                digest.update(bytes)
            }
        } else {
            opener().use { input -> input.readAtMost(digest, window.toInt()) }
            opener().use { input ->
                var remaining = size - window
                val discard = ByteArray(8192)
                while (remaining > 0) {
                    val skipped = input.skip(remaining)
                    if (skipped > 0) {
                        remaining -= skipped
                    } else {
                        val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        remaining -= read
                    }
                }
                input.readAtMost(digest, window.toInt())
            }
        }
        return FileFingerprint(digest.digest().toHex(), size, modified)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun InputStream.readAtMost(digest: MessageDigest, max: Int) {
        val buffer = ByteArray(8192)
        var remaining = max
        while (remaining > 0) {
            val count = read(buffer, 0, minOf(buffer.size, remaining))
            if (count <= 0) break
            digest.update(buffer, 0, count)
            remaining -= count
        }
    }

    private fun InputStream.readBounded(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(max)
        val buffer = ByteArray(8192)
        var remaining = max
        while (remaining > 0) {
            val count = read(buffer, 0, minOf(buffer.size, remaining))
            if (count <= 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }
}

class InternalStorageProvider(
    override val id: String,
    private val root: Path,
    override val displayName: String = "Internal storage",
) : StorageProvider {
    override val type = StorageType.INTERNAL
    override val isAvailable: Boolean get() = Files.isDirectory(root)
    override fun resolve(relativePath: String): StorageFile? {
        val path = safePath(relativePath)
        return path.takeIf { Files.isRegularFile(it) }?.let {
            StorageFile(id, relativePath, Files.size(it), Files.getLastModifiedTime(it).toMillis())
        }
    }
    override fun open(relativePath: String): InputStream = Files.newInputStream(safePath(relativePath))
    override fun write(relativePath: String): OutputStream {
        val path = safePath(relativePath)
        Files.createDirectories(path.parent)
        return Files.newOutputStream(path)
    }
    override fun delete(relativePath: String): Boolean = Files.deleteIfExists(safePath(relativePath))
    override fun fingerprint(relativePath: String): FileFingerprint = Fingerprints.forPath(safePath(relativePath))
    private fun safePath(relativePath: String): Path {
        require(!relativePath.startsWith('/') && !relativePath.contains("..")) { "relative path required" }
        return root.resolve(relativePath).normalize().also { require(it.startsWith(root.normalize())) }
    }
}

object DownloadReconciler {
    fun reconcile(record: DownloadRecord, storage: StorageProvider, now: Long): DownloadRecord {
        if (!storage.isAvailable) return record.copy(state = DownloadAvailability.STORAGE_UNAVAILABLE, lastVerifiedAt = now)
        val path = record.localPath ?: return record.copy(state = DownloadAvailability.MISSING, lastVerifiedAt = now)
        val file = storage.resolve(path) ?: return record.copy(state = DownloadAvailability.MISSING, lastVerifiedAt = now)
        val fingerprint = storage.fingerprint(path)
        val matches = record.checksum == null ||
            record.checksum == fingerprint.value ||
            // Older Kiroha recorded a names-and-lengths-only checksum. Accept it
            // once when it still matches the files, then migrate to the verified
            // current fingerprint below. Do not mark all existing copies as corrupt.
            runCatching { storage.legacyFingerprint(path) }.getOrNull() == record.checksum
        return record.copy(
            state = if (matches) DownloadAvailability.AVAILABLE_LOCAL else DownloadAvailability.ERROR,
            size = file.size,
            checksum = fingerprint.value,
            lastVerifiedAt = now,
        )
    }
}

/** Rebuilds the local projection without treating a missing/corrupt portable index as deletion. */
object StorageReindexer {
    fun reindexItem(
        records: Iterable<DownloadRecord>,
        storage: StorageProvider,
        deviceId: String,
        itemId: String,
        now: Long,
    ): List<DownloadRecord> = reconcile(records.filter { it.itemId == itemId }, storage, deviceId, now)

    fun reindexSeries(
        records: Iterable<DownloadRecord>,
        storage: StorageProvider,
        deviceId: String,
        itemIds: Set<String>,
        now: Long,
    ): List<DownloadRecord> = reconcile(records.filter { it.itemId in itemIds }, storage, deviceId, now)

    fun reindexStorage(
        records: Iterable<DownloadRecord>,
        storage: StorageProvider,
        deviceId: String,
        now: Long,
    ): List<DownloadRecord> = reconcile(records, storage, deviceId, now)

    fun reindexAll(
        records: Iterable<DownloadRecord>,
        storages: Iterable<StorageProvider>,
        deviceId: String,
        now: Long,
    ): List<DownloadRecord> = storages.flatMap { storage -> reindexStorage(records, storage, deviceId, now) }

    fun reconcile(
        records: Iterable<DownloadRecord>,
        storage: StorageProvider,
        deviceId: String,
        now: Long,
    ): List<DownloadRecord> {
        val local = records.filter { it.deviceId == deviceId && it.storageId == storage.id }
        if (!storage.isAvailable) {
            return local.map { it.copy(state = DownloadAvailability.STORAGE_UNAVAILABLE, lastVerifiedAt = now) }
        }

        val indexed = PortableStorageIndex(storage).readOrNull()
        val byItem = indexed?.associateBy { it.itemId }.orEmpty()
        val reconciled = local.map { record ->
            val entry = byItem[record.itemId]
            val candidate = if (entry == null) record else record.copy(
                localPath = entry.relativePath,
                size = entry.size,
                checksum = entry.fingerprint,
                downloadedEver = true,
            )
            DownloadReconciler.reconcile(candidate, storage, now)
        }.toMutableList()

        if (indexed != null) {
            val known = local.mapTo(hashSetOf()) { it.itemId }
            indexed.filterNot { it.itemId in known }.forEach { entry ->
                val file = storage.resolve(entry.relativePath) ?: return@forEach
                val fingerprint = runCatching { storage.fingerprint(entry.relativePath) }.getOrNull() ?: return@forEach
                reconciled += DownloadRecord(
                    itemId = entry.itemId,
                    deviceId = deviceId,
                    storageId = storage.id,
                    state = if (fingerprint.value == entry.fingerprint) DownloadAvailability.AVAILABLE_LOCAL else DownloadAvailability.ERROR,
                    downloadedEver = true,
                    localPath = entry.relativePath,
                    size = file.size,
                    checksum = fingerprint.value,
                    downloadedAt = now,
                    lastVerifiedAt = now,
                )
            }
        }
        return reconciled
    }
}
