package eu.kanade.tachiyomi.data.sync

import android.content.Context
import kiroha.core.AndroidDeviceInfo
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Unauthenticated only for creation/join; all subsequent operations use a device token. */
object KirohaSyncProvisioning {
    data class Result(val spaceId: String, val deviceToken: String, val pairingCode: String?)

    fun create(context: Context, serverUrl: String, name: String): Result {
        val response = request(serverUrl, "POST", "/sync-spaces", JSONObject()
            .put("name", name)
            .put("deviceId", stableDeviceId(context))
            .put("deviceName", AndroidDeviceInfo.name(context))
            .put("platform", "ANDROID")
            .put("appVersion", "0.1.0"))
        return Result(response.getString("spaceId"), response.getString("deviceToken"), response.optString("pairingCode").takeIf(String::isNotBlank))
    }

    fun join(context: Context, serverUrl: String, spaceId: String, pairingCode: String): Result {
        val response = request(serverUrl, "POST", "/sync-spaces/$spaceId/join", JSONObject()
            .put("pairingCode", pairingCode.trim())
            .put("deviceId", stableDeviceId(context))
            .put("deviceName", AndroidDeviceInfo.name(context))
            .put("platform", "ANDROID")
            .put("appVersion", "0.1.0"))
        return Result(response.getString("spaceId"), response.getString("deviceToken"), null)
    }

    fun test(context: Context, config: KirohaSyncConfig.Value) {
        AndroidHttpSyncBackend(config.serverUrl, config.deviceToken).heartbeat(config.spaceId)
    }

    private fun stableDeviceId(context: Context): String = kiroha.core.DeviceRegistry(
        kiroha.core.AndroidKeyValueStore(context),
    ).load()?.deviceId ?: error("Kiroha device registry is not initialized")

    private fun request(baseUrl: String, method: String, path: String, body: JSONObject): JSONObject {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("Kiroha Sync HTTP $status")
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
