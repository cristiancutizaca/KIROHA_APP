package eu.kanade.tachiyomi.data.sync

import kiroha.core.SyncBackend
import kiroha.core.SyncEntity
import kiroha.core.SyncEnvelope
import kiroha.core.SyncEvent
import kiroha.core.SyncOperation
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Android adapter for the portable Kiroha Sync HTTP protocol. Call from Dispatchers.IO. */
class AndroidHttpSyncBackend(
    private val baseUrl: String,
    private val deviceToken: String,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 15_000,
) : SyncBackend {
    override fun append(spaceId: String, event: SyncEvent): SyncEnvelope {
        val response = request(
            "POST",
            "/sync/events",
            JSONObject().put("spaceId", spaceId).put("event", eventJson(event)),
        )
        return envelope(spaceId, response)
    }

    override fun pull(spaceId: String, afterRevision: Long, limit: Int): List<SyncEnvelope> {
        val response = request("GET", "/sync/events?spaceId=$spaceId&afterRevision=$afterRevision&limit=$limit")
        val events = response.optJSONArray("events") ?: JSONArray()
        return buildList(events.length()) {
            for (index in 0 until events.length()) add(envelope(spaceId, events.getJSONObject(index)))
        }
    }

    override fun acknowledge(spaceId: String, deviceId: String, revision: Long) {
        request("POST", "/sync/ack", JSONObject().put("spaceId", spaceId).put("revision", revision))
    }

    fun heartbeat(spaceId: String) {
        request("POST", "/devices/heartbeat", JSONObject().put("spaceId", spaceId))
    }

    fun newPairing(spaceId: String): String = request("POST", "/sync-spaces/$spaceId/pairing").getString("pairingCode")

    fun devices(spaceId: String): List<String> {
        val values = request("GET", "/devices?spaceId=$spaceId").optJSONArray("devices") ?: return emptyList()
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val device = values.getJSONObject(index)
                add("${device.optString("deviceName")} · ${device.optString("platform")}")
            }
        }
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $deviceToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            body?.toString()?.toByteArray(Charsets.UTF_8)?.let(connection.outputStream::write)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("Kiroha Sync HTTP $status: $text")
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun eventJson(event: SyncEvent): JSONObject = JSONObject()
        .put("eventId", event.eventId)
        .put("entity", event.entity.name)
        .put("entityId", event.entityId)
        .put("operation", event.operation.name)
        .put("timestamp", event.timestamp)
        .put("payload", event.payload)

    private fun envelope(spaceId: String, json: JSONObject): SyncEnvelope = SyncEnvelope(
        spaceId = spaceId,
        revision = json.getLong("revision"),
        event = SyncEvent(
            eventId = json.getString("eventId"),
            entity = SyncEntity.valueOf(json.getString("entity")),
            entityId = json.getString("entityId"),
            operation = SyncOperation.valueOf(json.getString("operation")),
            revision = json.getLong("revision"),
            timestamp = json.getLong("timestamp"),
            deviceId = json.getString("originDeviceId"),
            payload = json.optString("payload"),
        ),
        originDeviceId = json.getString("originDeviceId"),
    )
}
