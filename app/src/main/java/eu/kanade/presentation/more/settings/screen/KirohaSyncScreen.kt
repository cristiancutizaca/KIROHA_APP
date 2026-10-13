package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.sync.AndroidHttpSyncBackend
import eu.kanade.tachiyomi.data.sync.KirohaSyncConfig
import eu.kanade.tachiyomi.data.sync.KirohaSyncJob
import eu.kanade.tachiyomi.data.sync.KirohaSyncProvisioning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold

object KirohaSyncScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val back = LocalBackPress.currentOrThrow
        val scope = rememberCoroutineScope()
        var config by remember { mutableStateOf(KirohaSyncConfig.read(context)) }
        var serverUrl by remember { mutableStateOf(config?.serverUrl ?: "http://192.168.1.2:8787") }
        var spaceId by remember { mutableStateOf(config?.spaceId.orEmpty()) }
        var pairingCode by remember { mutableStateOf("") }
        var status by remember { mutableStateOf(if (config == null) "Not configured" else "Configured") }
        var devices by remember { mutableStateOf(emptyList<String>()) }
        var generatedPairing by remember { mutableStateOf<String?>(null) }

        Scaffold(topBar = { AppBar(title = "Kiroha Sync", navigateUp = back) }) { padding ->
            Column(
                modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Private sync space. Tokens are protected by Android Keystore.")
                OutlinedTextField(serverUrl, { serverUrl = it }, Modifier.fillMaxWidth(), label = { Text("Server URL") }, singleLine = true)
                OutlinedTextField(spaceId, { spaceId = it }, Modifier.fillMaxWidth(), label = { Text("Sync Space ID") }, singleLine = true)
                Button(
                    onClick = {
                        scope.launch {
                            status = "Creating..."
                            runCatching {
                                withContext(Dispatchers.IO) { KirohaSyncProvisioning.create(context, serverUrl, "Kiroha private space") }
                            }.onSuccess {
                                KirohaSyncConfig.save(context, serverUrl, it.spaceId, it.deviceToken)
                                config = KirohaSyncConfig.read(context)
                                spaceId = it.spaceId
                                generatedPairing = it.pairingCode
                                status = "Space created and this device authorized"
                                KirohaSyncJob.enqueue(context)
                            }.onFailure { status = "Create failed: ${it.message ?: "unknown error"}" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create my Sync Space") }
                Spacer(Modifier.height(4.dp))
                Text("Join an existing space")
                OutlinedTextField(pairingCode, { pairingCode = it }, Modifier.fillMaxWidth(), label = { Text("Temporary pairing code") }, singleLine = true)
                Button(
                    onClick = {
                        scope.launch {
                            status = "Joining..."
                            runCatching {
                                withContext(Dispatchers.IO) { KirohaSyncProvisioning.join(context, serverUrl, spaceId, pairingCode) }
                            }.onSuccess {
                                KirohaSyncConfig.save(context, serverUrl, it.spaceId, it.deviceToken)
                                config = KirohaSyncConfig.read(context)
                                status = "Joined successfully"
                                pairingCode = ""
                                KirohaSyncJob.enqueue(context)
                            }.onFailure { status = "Join failed: ${it.message ?: "unknown error"}" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = serverUrl.isNotBlank() && spaceId.isNotBlank() && pairingCode.isNotBlank(),
                ) { Text("Join Sync Space") }
                if (generatedPairing != null) Text("New device pairing code: $generatedPairing")
                if (config != null) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                status = "Testing connection..."
                                runCatching { withContext(Dispatchers.IO) { KirohaSyncProvisioning.test(context, config!!) } }
                                    .onSuccess { status = "Connection OK" }
                                    .onFailure { status = "Connection failed: ${it.message ?: "server unavailable"}" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Test connection") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { AndroidHttpSyncBackend(config!!.serverUrl, config!!.deviceToken).newPairing(config!!.spaceId) } }
                                    .onSuccess { generatedPairing = it; status = "Pairing code generated" }
                                    .onFailure { status = "Pairing failed: ${it.message ?: "unknown error"}" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add device") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { AndroidHttpSyncBackend(config!!.serverUrl, config!!.deviceToken).devices(config!!.spaceId) } }
                                    .onSuccess { devices = it; status = "Devices updated" }
                                    .onFailure { status = "Devices failed: ${it.message ?: "unknown error"}" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Refresh my devices") }
                    devices.forEach { Text(it) }
                    Button(onClick = { KirohaSyncJob.enqueue(context); status = "Sync queued" }, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
                    OutlinedButton(
                        onClick = { KirohaSyncConfig.clear(context); config = null; generatedPairing = null; devices = emptyList(); status = "Sync configuration removed; local library kept" },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Leave Sync Space") }
                }
                Text(status)
            }
        }
    }
}
