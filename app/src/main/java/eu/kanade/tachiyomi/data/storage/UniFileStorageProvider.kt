package eu.kanade.tachiyomi.data.storage

import com.hippo.unifile.UniFile
import kiroha.core.FileFingerprint
import kiroha.core.StorageFile
import kiroha.core.StorageProvider
import kiroha.core.StorageType
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Android adapter for the active downloads tree; supports directory-based manga downloads. */
class UniFileStorageProvider(
    override val id: String,
    private val root: UniFile,
    override val type: StorageType = StorageType.INTERNAL,
    override val displayName: String = "Downloads",
) : StorageProvider {
    // A SAF directory lookup may list *every* child. A reindex of 2,000
    // galleries under one source otherwise lists that source directory 2,000
    // times (quadratic I/O). Cache ONLY the root and source-level children.
    // Never retain each gallery's image list: that would grow with page count.
    private val folderChildren = ConcurrentHashMap<String, Map<String, UniFile>>()

    override val isAvailable: Boolean get() = root.exists()

    override fun resolve(relativePath: String): StorageFile? = find(relativePath)?.let {
        StorageFile(id, clean(relativePath), it.sizeRecursively(), it.lastModified().coerceAtLeast(0L))
    }

    override fun open(relativePath: String): InputStream = requireFile(relativePath).openInputStream()

    override fun write(relativePath: String): OutputStream {
        // The portable index and externally created paths must not leave stale
        // directory snapshots after a write.
        folderChildren.clear()
        val parts = clean(relativePath).split('/')
        val parent = parts.dropLast(1).fold(root) { current, name ->
            current.findFile(name) ?: current.createDirectory(name) ?: error("Unable to create storage directory")
        }
        val file = parent.findFile(parts.last()) ?: parent.createFile(parts.last())
            ?: error("Unable to create storage file")
        return file.openOutputStream()
    }

    override fun delete(relativePath: String): Boolean {
        val deleted = find(relativePath)?.delete() == true
        if (deleted) folderChildren.clear()
        return deleted
    }

    override fun fingerprint(relativePath: String): FileFingerprint {
        val file = find(relativePath) ?: error("Storage file not found: $relativePath")
        val size = file.sizeRecursively()
        val modifiedAt = file.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$size:$modifiedAt".toByteArray())
        visit(file, file.name.orEmpty(), digest)
        return FileFingerprint(
            digest.digest().joinToString("") { "%02x".format(it) },
            size,
            modifiedAt.coerceAtLeast(0L),
        )
    }

    override fun legacyFingerprint(relativePath: String): String? {
        val file = find(relativePath) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        fun visitMetadata(current: UniFile, prefix: String) {
            if (current.isDirectory) {
                current.listFiles().orEmpty().sortedBy { it.name.orEmpty() }.forEach {
                    visitMetadata(it, "$prefix/${it.name.orEmpty()}")
                }
            } else {
                digest.update("$prefix:${current.length()}".toByteArray())
            }
        }
        visitMetadata(file, file.name.orEmpty())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun visit(file: UniFile, prefix: String, digest: MessageDigest) {
        if (file.isDirectory) {
            file.listFiles().orEmpty().sortedBy { it.name.orEmpty() }.forEach {
                visit(it, "$prefix/${it.name.orEmpty()}", digest)
            }
        } else {
            digest.update("$prefix:${file.length()}".toByteArray())
            file.openInputStream().use { input ->
                val buffer = ByteArray(8192)
                var remaining = 64 * 1024
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }

    private fun find(relativePath: String): UniFile? {
        var current = root
        clean(relativePath).split('/').forEachIndexed { depth, name ->
            val child = if (depth < 2) {
                // Cache source labels and their gallery folders for this
                // short-lived reindex provider; deeper paths remain uncached.
                val children = folderChildren.computeIfAbsent(current.uri.toString()) {
                    current.listFiles().orEmpty()
                        .filter { !it.name.isNullOrBlank() }
                        .associateBy { it.name!! }
                }
                children[name]
            } else {
                current.findFile(name)
            }
            current = child ?: return null
        }
        return current
    }

    private fun requireFile(relativePath: String): UniFile = find(relativePath)?.takeIf { !it.isDirectory }
        ?: error("Storage file not found: $relativePath")

    private fun clean(path: String): String = path.trim('/').also {
        require(it.isNotEmpty() && !it.contains("..") && !it.contains('\\')) { "relative path required" }
    }

    private fun UniFile.sizeRecursively(): Long = if (isDirectory) {
        listFiles().orEmpty().sumOf { it.sizeRecursively() }
    } else {
        length().coerceAtLeast(0L)
    }
}
