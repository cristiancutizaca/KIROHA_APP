package kiroha.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** Small rebuildable index stored under .kiroha; it is an accelerator, never an authority. */
class PortableStorageIndex(private val storage: StorageProvider) {
    data class Entry(val itemId: String, val relativePath: String, val fingerprint: String, val size: Long)

    fun read(): List<Entry> = readOrNull().orEmpty()

    fun readOrNull(): List<Entry>? = runCatching {
        storage.open(INDEX_PATH).use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).lineSequence()
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (line.startsWith("#") && parts[0] == "# Kiroha portable index v1; no secrets") null
                    else if (parts.size != 4 || parts.any(String::isEmpty)) error("Malformed portable index entry")
                    else Entry(parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: error("Invalid portable index size"))
                }.toList()
        }
    }.getOrNull()

    fun write(entries: Iterable<Entry>) {
        storage.write(INDEX_PATH).use { output ->
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                writer.appendLine("# Kiroha portable index v1; no secrets")
                entries.forEach { entry ->
                    require(!entry.itemId.contains('\t') && !entry.relativePath.contains('\t'))
                    writer.append(entry.itemId).append('\t').append(entry.relativePath).append('\t')
                        .append(entry.fingerprint).append('\t').append(entry.size.toString()).appendLine()
                }
            }
        }
    }

    companion object { const val INDEX_PATH = ".kiroha/index" }
}
