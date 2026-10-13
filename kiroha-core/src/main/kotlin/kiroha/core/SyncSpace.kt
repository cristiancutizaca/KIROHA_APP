package kiroha.core

/** A shared logical space; it is intentionally independent from Android and an HTTP provider. */
data class SyncSpace(
    val spaceId: String,
    val name: String,
    val createdAt: Long,
    val globalRevision: Long = 0,
)

data class SyncCursor(
    val spaceId: String,
    val deviceId: String,
    val revision: Long = 0,
    val updatedAt: Long = 0,
)

data class SyncEnvelope(
    val spaceId: String,
    val revision: Long,
    val event: SyncEvent,
    val originDeviceId: String = event.deviceId,
)

/** Transport boundary for a future HTTP/Cloud provider. Implementations must be idempotent by eventId. */
interface SyncBackend {
    fun append(spaceId: String, event: SyncEvent): SyncEnvelope
    fun pull(spaceId: String, afterRevision: Long, limit: Int = 500): List<SyncEnvelope>
    fun acknowledge(spaceId: String, deviceId: String, revision: Long)
}

/** Deterministic backend used by integration tests and local development. It models offline clients. */
class InMemorySyncBackend(
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncBackend {
    private data class SpaceState(
        var nextRevision: Long = 0,
        val events: MutableList<SyncEnvelope> = mutableListOf(),
        val eventIds: MutableMap<String, SyncEnvelope> = mutableMapOf(),
        val cursors: MutableMap<String, Long> = mutableMapOf(),
    )

    private val spaces = mutableMapOf<String, SpaceState>()

    @Synchronized
    override fun append(spaceId: String, event: SyncEvent): SyncEnvelope {
        val state = spaces.getOrPut(spaceId) { SpaceState() }
        state.eventIds[event.eventId]?.let { return it }
        val envelope = SyncEnvelope(spaceId, ++state.nextRevision, event)
        state.events += envelope
        state.eventIds[event.eventId] = envelope
        return envelope
    }

    @Synchronized
    override fun pull(spaceId: String, afterRevision: Long, limit: Int): List<SyncEnvelope> =
        spaces[spaceId]?.events
            ?.asSequence()
            ?.filter { it.revision > afterRevision }
            ?.take(limit.coerceAtLeast(1))
            ?.toList()
            .orEmpty()

    @Synchronized
    override fun acknowledge(spaceId: String, deviceId: String, revision: Long) {
        val state = spaces.getOrPut(spaceId) { SpaceState() }
        state.cursors[deviceId] = maxOf(state.cursors[deviceId] ?: 0, revision)
    }

    @Synchronized
    fun cursor(spaceId: String, deviceId: String): Long = spaces[spaceId]?.cursors?.get(deviceId) ?: 0

    @Synchronized
    fun globalRevision(spaceId: String): Long = spaces[spaceId]?.nextRevision ?: 0

    fun now(): Long = clock()
}

/** Client-side incremental sync. Applying a remote event never republishes it automatically. */
class SyncClient(
    private val spaceId: String,
    private val deviceId: String,
    private val backend: SyncBackend,
    private val clock: () -> Long = System::currentTimeMillis,
    initialCursor: SyncCursor = SyncCursor(spaceId, deviceId),
) {
    var cursor: SyncCursor = initialCursor
        private set

    fun push(events: Iterable<SyncEvent>): List<SyncEnvelope> = events
        .filter { it.deviceId == deviceId }
        .map { backend.append(spaceId, it) }

    fun pull(limit: Int = 500, applyRemote: (SyncEnvelope) -> Unit): List<SyncEnvelope> {
        val received = backend.pull(spaceId, cursor.revision, limit)
        received.forEach { envelope ->
            if (envelope.originDeviceId != deviceId) applyRemote(envelope)
            cursor = cursor.copy(revision = maxOf(cursor.revision, envelope.revision), updatedAt = clock())
        }
        if (received.isNotEmpty()) backend.acknowledge(spaceId, deviceId, cursor.revision)
        return received
    }
}
