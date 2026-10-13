package kiroha.core

/** Lightweight diagnostics that never includes bearer tokens, pairing codes or private paths. */
data class KirohaTiming(
    val operation: String,
    val startedAt: Long,
    val completedAt: Long,
    val outcome: String = "ok",
) {
    val durationMs: Long get() = (completedAt - startedAt).coerceAtLeast(0L)
}

class KirohaDiagnostics(private val clock: () -> Long = System::currentTimeMillis) {
    private val timings = mutableListOf<KirohaTiming>()

    fun <T> measure(operation: String, block: () -> T): T {
        val started = clock()
        return try {
            block().also { timings += KirohaTiming(operation, started, clock()) }
        } catch (error: Throwable) {
            timings += KirohaTiming(operation, started, clock(), error::class.simpleName ?: "error")
            throw error
        }
    }

    fun snapshot(): List<KirohaTiming> = timings.toList()

    fun exportText(
        deviceId: String?,
        spaceId: String?,
        pendingEvents: Int,
        storageStates: Map<String, String>,
    ): String = buildString {
        appendLine("Kiroha diagnostics v1")
        appendLine("device=${deviceId?.let(::shortId) ?: "unknown"}")
        appendLine("space=${spaceId?.let(::shortId) ?: "unknown"}")
        appendLine("pending=$pendingEvents")
        storageStates.toSortedMap().forEach { (id, state) -> appendLine("storage=${shortId(id)} state=$state") }
        timings.forEach { timing ->
            appendLine("timing=${timing.operation.replace(Regex("[^A-Za-z0-9_.-]"), "_")} durationMs=${timing.durationMs} outcome=${timing.outcome}")
        }
    }

    private fun shortId(value: String): String = value.take(4) + "…" + value.takeLast(4)
}
