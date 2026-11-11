// Copyright (c) 2026 Dominic Sykes. All rights reserved.
package com.quickdrop

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class Transfer(val name: String, val up: Boolean) {
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf("Waiting")
}

class Device(val name: String, val ip: String)

class Model {
    var message by mutableStateOf("")
    var deviceName by mutableStateOf("")
    var localIp by mutableStateOf("")
    var receiving by mutableStateOf(false)
    var receivePin by mutableStateOf("")
    var sendPin by mutableStateOf("")
    var manualIp by mutableStateOf("")
    var selectedIp by mutableStateOf<String?>(null)
    var scanning by mutableStateOf(false)
    var pendingCount by mutableIntStateOf(0)
    val devices = mutableStateListOf<Device>()
    val transfers = mutableStateListOf<Transfer>()
}

class MainActivity : ComponentActivity() {

    private val model = Model()
    private val prefs by lazy { getSharedPreferences("quickdrop", MODE_PRIVATE) }
    private var receiver: Receiver? = null
    private var pending: List<Uri> = emptyList()
    private val recvTransfers = ConcurrentHashMap<Long, Transfer>()

    private val listener = object : RecvListener {
        override fun begin(id: Long, name: String) = runOnUiThread {
            val t = Transfer(name, false)
            t.status = "Receiving…"
            recvTransfers[id] = t
            model.transfers.add(0, t)
        }

        override fun progress(id: Long, p: Float) {
            recvTransfers[id]?.progress = p
        }

        override fun done(id: Long) = runOnUiThread {
            recvTransfers.remove(id)?.let {
                it.progress = 1f
                it.status = "Saved to Downloads/QuickDrop"
            }
        }

        override fun fail(id: Long, msg: String) = runOnUiThread {
            recvTransfers.remove(id)?.status = "Failed: $msg"
        }

        override fun info(msg: String) = runOnUiThread { model.message = msg }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) sendUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.deviceName = Build.MODEL ?: "Android"
        model.receivePin = prefs.getString("receivePin", "") ?: ""
        model.sendPin = prefs.getString("sendPin", "") ?: ""
        model.manualIp = prefs.getString("manualIp", "") ?: ""
        Net.bindToWifi(this)
        model.localIp = Proto.localIp() ?: ""

        setContent {
            MaterialTheme {
                Screen(
                    m = model,
                    onToggleReceive = { if (it) startReceiving() else stopReceiving() },
                    onReceivePin = {
                        model.receivePin = it
                        receiver?.pin = it
                        prefs.edit().putString("receivePin", it).apply()
                    },
                    onSendPin = {
                        model.sendPin = it
                        prefs.edit().putString("sendPin", it).apply()
                    },
                    onManualIp = {
                        model.manualIp = it
                        prefs.edit().putString("manualIp", it).apply()
                    },
                    onScan = { scan() },
                    onTest = { testConnection() },
                    onSelect = { selectDevice(it.ip) },
                    onPick = { picker.launch(arrayOf("*/*")) },
                    onClear = { model.transfers.clear() }
                )
            }
        }

        startReceiving()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Net.bindToWifi(this)
        model.localIp = Proto.localIp() ?: ""
    }

    override fun onDestroy() {
        receiver?.stop()
        super.onDestroy()
    }

    // ---------- receiving ----------

    private fun startReceiving() {
        try {
            val r = Receiver(this, model.deviceName, listener)
            r.pin = model.receivePin
            r.start()
            receiver = r
            model.receiving = true
        } catch (e: Exception) {
            model.receiving = false
            model.message = "Could not start receiving: ${e.message}"
        }
    }

    private fun stopReceiving() {
        receiver?.stop()
        receiver = null
        model.receiving = false
    }

    // ---------- finding devices ----------

    private fun scan() {
        if (model.scanning) return
        model.scanning = true
        model.message = "Looking for devices on your Wi-Fi…"
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { Sender.scan(Proto.localIps()) }
            model.devices.clear()
            found.forEach { model.devices.add(Device(it.second, it.first)) }
            model.scanning = false
            model.message = if (found.isEmpty())
                "No devices found. Open QuickDrop on the other device, or type its IP address."
            else "Found ${found.size} device(s)"
            if (found.size == 1 && model.manualIp.isBlank()) selectDevice(found[0].first)
        }
    }

    private fun testConnection() {
        val host = targetHost()
        if (host.isEmpty()) {
            model.message = "Pick a device or type its IP address first"
            return
        }
        model.message = "Testing $host…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { Sender.probe(host) }
            model.message = result
        }
    }

    private fun selectDevice(ip: String) {
        model.selectedIp = ip
        if (pending.isNotEmpty()) {
            val list = pending
            pending = emptyList()
            model.pendingCount = 0
            sendUris(list)
        }
    }

    // ---------- sending ----------

    private fun targetHost(): String = model.manualIp.trim().ifEmpty { model.selectedIp ?: "" }

    private fun describe(uri: Uri): OutFile {
        var name = "file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) { // size unknown: count the bytes
            size = 0
            contentResolver.openInputStream(uri)?.use { s ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    size += n
                }
            }
        }
        return OutFile(uri, name, size)
    }

    private fun sendUris(uris: List<Uri>) {
        val host = targetHost()
        if (host.isEmpty()) {
            pending = uris
            model.pendingCount = uris.size
            model.message = "Pick the device to send ${uris.size} file(s) to"
            scan()
            return
        }
        lifecycleScope.launch {
            val items = try {
                withContext(Dispatchers.IO) { uris.map { describe(it) } }
            } catch (e: Exception) {
                model.message = "Could not read the files: ${e.message}"
                return@launch
            }
            val ts = items.map { Transfer(it.name, true) }
            ts.asReversed().forEach { model.transfers.add(0, it) }
            ts.forEach { it.status = "Sending…" }
            try {
                withContext(Dispatchers.IO) {
                    Sender.send(this@MainActivity, host, items, model.sendPin) { idx, p -> ts[idx].progress = p }
                }
                ts.forEach {
                    it.progress = 1f
                    it.status = "Sent"
                }
            } catch (e: Exception) {
                ts.forEach {
                    it.status = if (it.progress >= 1f) "Sent" else "Failed: ${e.message}"
                }
                model.message = e.message ?: "Transfer failed"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamUris(i: Intent): List<Uri> {
        return if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
        } else {
            val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            listOfNotNull(u)
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = streamUris(i)
        if (uris.isNotEmpty()) sendUris(uris)
    }
}

@Composable
fun Screen(
    m: Model,
    onToggleReceive: (Boolean) -> Unit,
    onReceivePin: (String) -> Unit,
    onSendPin: (String) -> Unit,
    onManualIp: (String) -> Unit,
    onScan: () -> Unit,
    onTest: () -> Unit,
    onSelect: (Device) -> Unit,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("QuickDrop", style = MaterialTheme.typography.headlineMedium)
        if (m.message.isNotEmpty()) {
            Text(m.message, color = MaterialTheme.colorScheme.primary)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Receive", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Receive files on this phone")
                    Switch(checked = m.receiving, onCheckedChange = onToggleReceive)
                }
                if (m.localIp.isEmpty()) {
                    Text("This phone isn’t connected to Wi-Fi. Connect to the same Wi-Fi as your PC.", color = MaterialTheme.colorScheme.error)
                } else if (m.receiving) {
                    Text("This phone: ${m.deviceName}  (${m.localIp}:${Proto.PORT})", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = m.receivePin,
                    onValueChange = onReceivePin,
                    label = { Text("PIN senders must enter (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Files are saved to Downloads/QuickDrop. Keep this app open while receiving.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Send", style = MaterialTheme.typography.titleMedium)
                if (m.pendingCount > 0) {
                    Text("${m.pendingCount} file(s) ready. Pick a device below.", color = MaterialTheme.colorScheme.primary)
                }
                Button(onClick = onScan, enabled = !m.scanning) {
                    Text(if (m.scanning) "Looking…" else "Find devices")
                }
                m.devices.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = m.manualIp.isBlank() && m.selectedIp == d.ip,
                            onClick = { onSelect(d) }
                        )
                        Text("${d.name}  (${d.ip})")
                    }
                }
                OutlinedTextField(
                    value = m.manualIp,
                    onValueChange = onManualIp,
                    label = { Text("Or type the IP address, e.g. 192.168.1.42") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = m.sendPin,
                    onValueChange = onSendPin,
                    label = { Text("PIN of the receiver (if it has one)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = onTest) { Text("Test connection") }
                Button(onClick = onPick) { Text("Choose files to send") }
                Text(
                    "You can also use Share in any app and pick QuickDrop.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (m.transfers.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Transfers", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClear) { Text("Clear") }
            }
            m.transfers.forEach { t ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text((if (t.up) "↑ " else "↓ ") + t.name)
                    LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
                    Text(t.status, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text(
            "QuickDrop  ©  2026 Dominic Sykes. All rights reserved.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
    }
}
