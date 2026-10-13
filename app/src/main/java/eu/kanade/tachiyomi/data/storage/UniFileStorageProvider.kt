package eu.kanade.tachiyomi.data.storage

import com.hippo.unifile.UniFile
import kiroha.core.FileFingerprint
import kiroha.core.StorageFile
import kiroha.core.StorageProvider
import kiroha.core.StorageType
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Android adapter for the active downloads tree; supports directory-based manga downloads. */
class UniFileStorageProvider(
    override val id: String,
    private val root: UniFile,
    override val type: StorageType = StorageType.INTERNAL,
    override val displayName: String = "Downloads",
) : StorageProvider {
    override val isAvailable: Boolean get() = root.exists()

    override fun resolve(relativePath: String): StorageFile? = find(relativePath)?.let {
        StorageFile(id, clean(relativePath), it.sizeRecursively(), it.lastModified().coerceAtLeast(0L))
    }

    override fun open(relativePath: String): InputStream = requireFile(relativePath).openInputStream()

    override fun write(relativePath: String): OutputStream {
        val parts = clean(relativePath).split('/')
        val parent = parts.dropLast(1).fold(root) { current, name ->
            current.findFile(name) ?: current.createDirectory(name) ?: error("Unable to create storage directory")
        }
        val file = parent.findFile(parts.last()) ?: parent.createFile(parts.last())
            ?: error("Unable to create storage file")
        return file.openOutputStream()
    }

    override fun delete(relativePath: String): Boolean = find(relativePath)?.delete() == true

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

    private fun find(relativePath: String): UniFile? = clean(relativePath).split('/').fold(root) { current, name ->
        current.findFile(name) ?: return null
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
