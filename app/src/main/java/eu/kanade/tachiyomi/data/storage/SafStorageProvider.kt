package eu.kanade.tachiyomi.data.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kiroha.core.FileFingerprint
import kiroha.core.StorageFile
import kiroha.core.StorageProvider
import kiroha.core.StorageType
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kiroha.core.Fingerprints

/** SAF tree provider. The persisted tree URI is a capability, never a Sync secret. */
class SafStorageProvider(
    private val context: Context,
    override val id: String,
    private val treeUri: Uri,
    override val displayName: String = "SD card",
) : StorageProvider {
    private val resolver get() = context.contentResolver
    override val type = StorageType.SAF_TREE
    override val isAvailable: Boolean
        get() = runCatching { resolver.query(rootChildren(), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { true } ?: false }.getOrDefault(false)

    override fun resolve(relativePath: String): StorageFile? = find(relativePath)?.let { document ->
        StorageFile(id, relativePath, document.size, document.modified)
    }

    override fun open(relativePath: String): InputStream = resolver.openInputStream(requireDocument(relativePath))
        ?: error("Unable to open SAF document")

    override fun write(relativePath: String): OutputStream {
        val parts = clean(relativePath).split('/')
        val fileName = parts.last()
        val parent = ensureDirectory(parts.dropLast(1))
        val uri = findChild(parent, fileName) ?: DocumentsContract.createDocument(resolver, parent, "application/octet-stream", fileName)
            ?: error("Unable to create SAF document")
        return resolver.openOutputStream(uri, "w") ?: error("Unable to write SAF document")
    }

    override fun delete(relativePath: String): Boolean = find(relativePath)?.uri?.let {
        DocumentsContract.deleteDocument(resolver, it)
    } ?: false

    override fun fingerprint(relativePath: String): FileFingerprint {
        val document = requireDocument(relativePath)
        val metadata = query(document) ?: error("Unable to query SAF document")
        return Fingerprints.forStreams(metadata.size, metadata.modified) {
            resolver.openInputStream(document) ?: error("Unable to fingerprint SAF document")
        }
    }

    private data class Document(val uri: Uri, val size: Long, val modified: Long)

    private fun clean(path: String): String = path.trim('/').also { require(it.isNotEmpty() && !it.contains("..")) }
    private fun rootDocument(): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    private fun rootChildren(): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun find(path: String): Document? {
        var parent = rootDocument()
        clean(path).split('/').forEach { name ->
            parent = findChild(parent, name) ?: return null
        }
        return query(parent)
    }

    private fun requireDocument(path: String): Uri = find(path)?.uri ?: error("SAF document not found: $path")

    private fun ensureDirectory(parts: List<String>): Uri {
        var parent = rootDocument()
        parts.filter(String::isNotBlank).forEach { name ->
            parent = findChild(parent, name) ?: DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
                ?: error("Unable to create SAF directory")
        }
        return parent
    }

    private fun findChild(parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) if (cursor.getString(nameColumn) == name) {
                return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
            }
            null
        }
    }

    private fun query(uri: Uri): Document? = resolver.query(uri, arrayOf(
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    ), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        Document(uri, cursor.getLong(0).coerceAtLeast(0), cursor.getLong(1).coerceAtLeast(0))
    }
}

object SafStorageRegistry {
    private const val PREFS = "kiroha-storages"
    private const val KEY = "saf.uris"

    fun register(context: Context, uri: Uri, displayName: String = "SD card"): String {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val id = "saf_" + sha256(uri.toString()).take(16)
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        values += "$id|${uri}|$displayName"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, values).apply()
        return id
    }

    fun providers(context: Context): List<SafStorageProvider> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY, emptySet()).orEmpty().mapNotNull { value ->
            val parts = value.split('|', limit = 3)
            if (parts.size != 3) null else SafStorageProvider(context, parts[0], Uri.parse(parts[1]), parts[2])
        }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
