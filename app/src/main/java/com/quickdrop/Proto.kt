// Copyright (c) 2026 Dominic Sykes. All rights reserved.
package com.quickdrop

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLConnection
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/*
 * QuickDrop protocol (same as the Python QuickDrop scripts and the Windows app):
 *   TCP 5005: sender sends one JSON line {"pin": "...", "files": [{"name": "...", "size": N}, ...]}\n
 *             receiver answers "OK\n" (or "ERR wrong PIN\n"), sender streams the raw bytes of each file in order,
 *             receiver answers "DONE\n".
 *   UDP 5006: sender broadcasts "QUICKDROP?", receivers answer "QUICKDROP!<device name>".
 */
object Proto {
    const val PORT = 5005
    const val DISCOVERY_PORT = 5006
    const val CHUNK = 64 * 1024

    fun readLine(ins: InputStream, limit: Int = 1_000_000): String {
        val bo = ByteArrayOutputStream()
        while (true) {
            val b = ins.read()
            if (b < 0) throw IOException("connection closed")
            if (b == '\n'.code) break
            bo.write(b)
            if (bo.size() > limit) throw IOException("header too large")
        }
        return bo.toString("UTF-8")
    }

    /** IPv4 addresses of this device, Wi-Fi first (mobile-data addresses last). */
    fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy {
                when {
                    it.name.startsWith("wlan") -> 0
                    it.name.startsWith("eth") || it.name.startsWith("ap") -> 1
                    else -> 2
                }
            }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }

    fun localIp(): String? = localIps().firstOrNull()

    fun safeName(raw: String): String {
        val n = raw.replace('\\', '/').substringAfterLast('/').trim().trim('.')
        return if (n.isEmpty()) "file" else n
    }
}

object Net {
    /**
     * If the Wi-Fi has no internet, Android may send app traffic over mobile data instead,
     * which can never reach the PC. Pinning the app to Wi-Fi avoids that.
     */
    @Suppress("DEPRECATION")
    fun bindToWifi(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifi != null) cm.bindProcessToNetwork(wifi)
        } catch (_: Exception) {
        }
    }
}

class OutFile(val uri: Uri, val name: String, val size: Long)

object Sender {

    /** Broadcasts on the Wi-Fi and returns a list of (ip, device name). */
    fun scan(ownIps: List<String>, seconds: Int = 2): List<Pair<String, String>> {
        val found = LinkedHashMap<String, String>()
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.soTimeout = 500
            val msg = "QUICKDROP?".toByteArray()
            val targets = ArrayList<InetAddress>()
            targets.add(InetAddress.getByName("255.255.255.255"))
            ownIps.forEach { ip ->
                val parts = ip.split(".")
                if (parts.size == 4) targets.add(InetAddress.getByName(parts[0] + "." + parts[1] + "." + parts[2] + ".255"))
            }
            val ping = {
                targets.forEach { t ->
                    try {
                        socket.send(DatagramPacket(msg, msg.size, t, Proto.DISCOVERY_PORT))
                    } catch (_: IOException) {
                    }
                }
            }
            ping()
            val end = System.currentTimeMillis() + seconds * 1000L
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val text = String(p.data, 0, p.length)
                    val ip = p.address.hostAddress
                    if (ip != null && text.startsWith("QUICKDROP!") && ip !in ownIps) {
                        found[ip] = text.removePrefix("QUICKDROP!")
                    }
                } catch (_: SocketTimeoutException) {
                    ping()
                }
            }
        } catch (_: IOException) {
        } finally {
            socket.close()
        }
        return found.map { it.key to it.value }
    }

    /** Tries to open a connection to host:5005 and returns a plain-English result. */
    fun probe(host: String): String {
        return try {
            Socket().use { it.connect(InetSocketAddress(host, Proto.PORT), 4000) }
            "Reached $host. QuickDrop is listening there."
        } catch (e: java.net.ConnectException) {
            "$host answered but QuickDrop isn't listening. Is it running? Is the firewall blocking it?"
        } catch (e: SocketTimeoutException) {
            "No answer from $host. Check the address, that both devices use the same Wi-Fi, and the PC firewall."
        } catch (e: Exception) {
            "Could not reach $host: ${e.message}"
        }
    }

    fun send(ctx: Context, host: String, files: List<OutFile>, pin: String, onProgress: (Int, Float) -> Unit) {
        Socket().use { s ->
            try {
                s.connect(InetSocketAddress(host, Proto.PORT), 10000)
            } catch (e: IOException) {
                throw IOException("Could not connect to $host. Is QuickDrop running there?")
            }
            s.soTimeout = 60000
            val out = s.getOutputStream()
            val ins = s.getInputStream()

            val arr = JSONArray()
            files.forEach { arr.put(JSONObject().put("name", it.name).put("size", it.size)) }
            val header = JSONObject().put("pin", pin).put("files", arr).toString() + "\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.flush()

            val reply = Proto.readLine(ins).trim()
            if (reply != "OK") {
                throw IOException(
                    if (reply.startsWith("ERR ")) "receiver refused: " + reply.removePrefix("ERR ")
                    else "unexpected reply from receiver"
                )
            }

            val buf = ByteArray(Proto.CHUNK)
            files.forEachIndexed { idx, f ->
                var sent = 0L
                var last = 0f
                val src = ctx.contentResolver.openInputStream(f.uri) ?: throw IOException("Cannot open ${f.name}")
                src.use {
                    while (sent < f.size) {
                        val n = it.read(buf, 0, minOf(buf.size.toLong(), f.size - sent).toInt())
                        if (n < 0) throw IOException("${f.name} changed while sending")
                        out.write(buf, 0, n)
                        sent += n
                        val p = sent.toFloat() / f.size
                        if (p - last >= 0.005f) {
                            last = p
                            onProgress(idx, p)
                        }
                    }
                }
                onProgress(idx, 1f)
            }
            out.flush()

            val done = Proto.readLine(ins).trim()
            if (done != "DONE") throw IOException("receiver did not confirm the transfer")
        }
    }
}

interface RecvListener {
    fun begin(id: Long, name: String)
    fun progress(id: Long, p: Float)
    fun done(id: Long)
    fun fail(id: Long, msg: String)
    fun info(msg: String)
}

/** Listens for files from other QuickDrop devices and saves them to Downloads/QuickDrop. */
class Receiver(private val ctx: Context, private val deviceName: String, private val listener: RecvListener) {

    @Volatile
    var pin: String = ""

    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val ids = AtomicLong(1)

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(Proto.PORT)) // throws if the port is busy
        server = ss
        running = true

        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("quickdrop").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }

        thread(name = "qd-accept", isDaemon = true) {
            while (running) {
                val c = try {
                    ss.accept()
                } catch (_: IOException) {
                    break
                }
                thread(name = "qd-conn", isDaemon = true) { handle(c) }
            }
        }
        thread(name = "qd-discovery", isDaemon = true) { discoveryLoop() }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        try {
            udp?.close()
        } catch (_: Exception) {
        }
        try {
            lock?.release()
        } catch (_: Exception) {
        }
        server = null
        udp = null
        lock = null
    }

    private fun discoveryLoop() {
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(Proto.DISCOVERY_PORT))
            udp = s
            val reply = ("QUICKDROP!" + deviceName).toByteArray()
            val buf = ByteArray(1024)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                if (String(p.data, 0, p.length).trim() == "QUICKDROP?") {
                    s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                }
            }
        } catch (_: Exception) {
            // socket closed or discovery unavailable: typing the IP still works
        }
    }

    private fun handle(sock: Socket) {
        sock.use {
            it.soTimeout = 30000
            val remote = it.inetAddress?.hostAddress ?: "unknown"
            var currentId = 0L
            var pending: Uri? = null
            try {
                val ins = it.getInputStream()
                val out = it.getOutputStream()
                val header = JSONObject(Proto.readLine(ins))
                val given = if (header.has("pin") && !header.isNull("pin")) header.get("pin").toString() else ""
                val myPin = pin
                if (myPin.isNotEmpty() && !MessageDigest.isEqual(given.toByteArray(), myPin.toByteArray())) {
                    out.write("ERR wrong PIN\n".toByteArray())
                    out.flush()
                    listener.info("$remote tried a wrong PIN")
                    return
                }
                val files = header.getJSONArray("files")
                out.write("OK\n".toByteArray())
                out.flush()
                listener.info("Receiving ${files.length()} file(s) from $remote")

                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val name = Proto.safeName(f.getString("name"))
                    val size = f.getLong("size")
                    currentId = ids.getAndIncrement()
                    listener.begin(currentId, name)

                    val (uri, os) = createTarget(name)
                    pending = uri
                    var got = 0L
                    var last = 0f
                    os.use { dst ->
                        val buf = ByteArray(Proto.CHUNK)
                        while (got < size) {
                            val n = ins.read(buf, 0, minOf(buf.size.toLong(), size - got).toInt())
                            if (n < 0) throw IOException("connection lost mid-file")
                            dst.write(buf, 0, n)
                            got += n
                            val p = if (size == 0L) 1f else got.toFloat() / size
                            if (p - last >= 0.005f) {
                                last = p
                                listener.progress(currentId, p)
                            }
                        }
                    }
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    ctx.contentResolver.update(uri, done, null, null)
                    pending = null
                    listener.done(currentId)
                    currentId = 0L
                }
                out.write("DONE\n".toByteArray())
                out.flush()
            } catch (e: Exception) {
                pending?.let { u ->
                    try {
                        ctx.contentResolver.delete(u, null, null)
                    } catch (_: Exception) {
                    }
                }
                if (currentId != 0L) listener.fail(currentId, e.message ?: "error")
                else listener.info("Transfer from $remote failed: ${e.message}")
            }
        }
    }

    private fun createTarget(name: String): Pair<Uri, OutputStream> {
        val mime = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QuickDrop")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create file in Downloads")
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot write file")
        return uri to os
    }
}
