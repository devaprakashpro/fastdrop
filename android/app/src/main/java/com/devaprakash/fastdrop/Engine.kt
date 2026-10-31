package com.devaprakash.fastdrop

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

const val CHUNK = 8L shl 20
const val STREAMS = 4
const val BUF = 1 shl 20
const val BEACON_PORT = 8766

enum class LinkMode { BOTH, WIFI, CABLE }

class SendItem(val name: String, val size: Long, val pfd: ParcelFileDescriptor)

class Xfer(val id: String, val name: String, val size: Long, val up: Boolean) {
    val done = AtomicLong()
    @Volatile var start = System.currentTimeMillis()
    @Volatile var end = 0L
    @Volatile var error: String? = null
    @Volatile var started = false
    @Volatile var uri: Uri? = null
    val active get() = end == 0L && error == null
}

class Resp(val status: Int, val length: Long)

/** Minimal keep-alive HTTP/1.1 client on a raw socket: big buffers, no framework overhead. */
class Conn(private val host: String, port: Int) : Closeable {
    private val s = Socket()
    val out: OutputStream
    val inp: InputStream

    init {
        s.tcpNoDelay = true
        s.sendBufferSize = 4 shl 20
        s.receiveBufferSize = 4 shl 20
        s.connect(InetSocketAddress(host, port), 3000)
        s.soTimeout = 20000
        out = s.getOutputStream()
        inp = BufferedInputStream(s.getInputStream(), 64 * 1024)
    }

    private fun line(): String {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) throw EOFException()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    fun response(): Resp {
        val status = line().split(" ")[1].toInt()
        var len = -1L
        while (true) {
            val l = line()
            if (l.isEmpty()) break
            if (l.startsWith("content-length:", true)) len = l.substring(15).trim().toLong()
        }
        return Resp(status, len)
    }

    fun put(path: String, len: Long, body: (OutputStream) -> Unit) {
        out.write("PUT $path HTTP/1.1\r\nHost: $host\r\nContent-Length: $len\r\n\r\n".toByteArray())
        body(out)
        out.flush()
        val r = response()
        if (r.length > 0) inp.skip(r.length)
        if (r.status != 200) throw IOException("PC replied HTTP ${r.status}")
    }

    fun benchUp() {
        out.write("PUT /bench/up HTTP/1.1\r\nHost: $host\r\nContent-Length: 1099511627776\r\n\r\n".toByteArray())
    }

    fun benchDown() {
        out.write("GET /bench/down HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
        out.flush()
        response()
    }

    fun get(path: String, from: Long, to: Long): Resp {
        out.write("GET $path HTTP/1.1\r\nHost: $host\r\nRange: bytes=$from-$to\r\n\r\n".toByteArray())
        out.flush()
        val r = response()
        if (r.status != 206 && r.status != 200) throw IOException("PC replied HTTP ${r.status}")
        return r
    }

    override fun close() {
        try { s.close() } catch (_: Exception) {}
    }

    fun readBody(r: Resp, buf: ByteArray, sink: (Int) -> Unit) {
        var rem = r.length
        while (rem > 0) {
            val n = inp.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
            if (n < 0) throw EOFException()
            sink(n)
            rem -= n
        }
    }
}

class Engine(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("fd", Context.MODE_PRIVATE)
    @Volatile var host: String? = prefs.getString("host", null)
    @Volatile var port = prefs.getInt("port", 8765)
    @Volatile var pcName: String = prefs.getString("name", "") ?: ""
    @Volatile var connected = false
    @Volatile var uiVisible = false
    @Volatile var autoReceive = prefs.getBoolean("auto", true)
        set(v) { field = v; prefs.edit().putBoolean("auto", v).apply() }

    /** Lifetime byte counters; the UI differentiates them into live up/down speed. */
    val upBytes = AtomicLong()
    val downBytes = AtomicLong()
    @Volatile var streams = prefs.getInt("streams2", STREAMS)
    @Volatile var usbUp = false
    @Volatile var mode: LinkMode = try { LinkMode.valueOf(prefs.getString("mode", "BOTH")!!) } catch (_: Exception) { LinkMode.BOTH }
        set(v) { field = v; prefs.edit().putString("mode", v.name).apply() }
    @Volatile var benchMsg = ""
    val benching = AtomicBoolean(false)
    @Volatile var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile var hotspotSsid = ""
    @Volatile var hotspotPass = ""

    val xfers = CopyOnWriteArrayList<Xfer>()
    val busy get() = xfers.any { it.active }

    private val seen = HashSet<String>(prefs.getStringSet("seen", emptySet()) ?: emptySet())
    private val running = AtomicBoolean(false)
    private val sendQ = Executors.newSingleThreadExecutor()
    private val recvQ = Executors.newSingleThreadExecutor()
    private var sock: DatagramSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "fd-poll", isDaemon = true) { poll() }
        thread(name = "fd-disc", isDaemon = true) { discover() }
    }

    fun stop() {
        running.set(false)
        try { sock?.close() } catch (_: Exception) {}
        try { hotspot?.close() } catch (_: Exception) {}
        hotspot = null
    }

    fun setManualHost(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val p = t.split(":")
        host = p[0]
        port = p.getOrNull(1)?.toIntOrNull() ?: 8765
        connected = false
    }

    /** Wi-Fi (or manual) address, plus the USB tunnel (adb reverse) when it reaches the same PC. */
    private fun paths(): List<String> {
        val h = host ?: return emptyList()
        return when (mode) {
            LinkMode.BOTH -> if (usbUp && h != "127.0.0.1") listOf(h, "127.0.0.1") else listOf(h)
            LinkMode.WIFI -> listOf(h)
            LinkMode.CABLE -> if (usbUp) listOf("127.0.0.1") else listOf(h)
        }
    }

    private fun probeUsb() {
        val h = host
        if (h == null || h == "127.0.0.1") { usbUp = false; return }
        usbUp = try {
            val c = URL("http://127.0.0.1:$port/api/state?dev=" + URLEncoder.encode(Build.MODEL, "UTF-8")).openConnection() as HttpURLConnection
            c.connectTimeout = 400
            c.readTimeout = 800
            val name = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("name")
            c.disconnect()
            name.isNotEmpty() && name == pcName
        } catch (_: Exception) { false }
    }

    private fun friendly(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("ECONNREFUSED") || m.contains("failed to connect") || m.contains("timed out") ||
                m.contains("ENETUNREACH") || m.contains("EHOSTUNREACH") || m.contains("not connected") ->
                "Can't reach the PC. Open FastDrop on the PC (same Wi-Fi) and send again."
            m.contains("EPIPE") || m.contains("Connection reset") || m.contains("Broken pipe") ->
                "Connection to the PC dropped. Send again."
            else -> m
        }
    }

    private fun waitConnected(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!connected && System.currentTimeMillis() < end) Thread.sleep(200)
        if (!connected) throw IOException("PC not connected")
    }

    /** QR / deep link: fastdrop://connect?name=..&port=..&ip=a&ip=b — try every address, keep the fastest. */
    fun connectFromQr(uri: Uri, done: (String) -> Unit) {
        val ips = uri.getQueryParameters("ip")
        val p = uri.getQueryParameter("port")?.toIntOrNull() ?: 8765
        val nm = uri.getQueryParameter("name") ?: ""
        if (ips.isEmpty()) { done("That QR code has no PC address"); return }
        thread(isDaemon = true) {
            val ok = CopyOnWriteArrayList<Pair<String, Long>>()
            val dev = URLEncoder.encode(Build.MODEL, "UTF-8")
            val ths = ips.map { ip ->
                thread(isDaemon = true) {
                    val t0 = System.nanoTime()
                    try {
                        val c = URL("http://$ip:$p/api/state?dev=$dev").openConnection() as HttpURLConnection
                        c.connectTimeout = 1500
                        c.readTimeout = 1500
                        c.inputStream.use { it.readBytes() }
                        c.disconnect()
                        ok.add(ip to (System.nanoTime() - t0) / 1_000_000)
                    } catch (_: Exception) {}
                }
            }
            ths.forEach { it.join(2200) }
            val best = ok.minByOrNull { it.second }
            if (best == null) {
                done("Couldn't reach the PC on any of its addresses. Same Wi-Fi?")
            } else {
                host = best.first
                port = p
                if (nm.isNotEmpty()) pcName = nm
                connected = false
                val others = ok.filter { it.first != best.first }.joinToString { it.first }
                done("Connected via ${best.first}" + if (others.isNotEmpty()) " (also reachable: $others)" else "")
            }
        }
    }

    fun clearDone() { xfers.removeAll(xfers.filter { !it.active }.toSet()) }

    // ---- discovery ---------------------------------------------------------------------------

    private fun discover() {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("fastdrop").also { it.setReferenceCounted(false); it.acquire() }
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(BEACON_PORT))
            s.soTimeout = 1000
            sock = s
            val buf = ByteArray(512)
            val probe = "FASTDROP?".toByteArray()
            var lastProbe = 0L
            while (running.get()) {
                val now = System.currentTimeMillis()
                if (!connected && now - lastProbe > 1500) {
                    try {
                        s.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), BEACON_PORT))
                    } catch (_: Exception) {}
                    lastProbe = now
                }
                try {
                    val pk = DatagramPacket(buf, buf.size)
                    s.receive(pk)
                    val m = String(pk.data, 0, pk.length)
                    if (m.startsWith("FASTDROP1|") && !connected) {
                        val parts = m.split("|")
                        host = pk.address.hostAddress
                        port = parts.getOrNull(2)?.toIntOrNull() ?: 8765
                        pcName = parts.getOrNull(1) ?: ""
                    }
                } catch (_: SocketTimeoutException) {}
            }
        } catch (_: Exception) {
        } finally {
            lock.release()
        }
    }

    // ---- polling the PC for files it wants to send us -------------------------------------------

    private fun poll() {
        var fails = 0
        var tick = 0
        while (running.get()) {
            val h = host
            if (h != null) {
                try {
                    val dev = URLEncoder.encode(Build.MODEL, "UTF-8")
                    val c = URL("http://$h:$port/api/state?dev=$dev").openConnection() as HttpURLConnection
                    c.connectTimeout = 1500
                    c.readTimeout = 2500
                    val txt = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    val j = JSONObject(txt)
                    pcName = j.optString("name", pcName)
                    if (!connected) prefs.edit().putString("host", h).putInt("port", port).putString("name", pcName).apply()
                    connected = true
                    fails = 0
                    val ob = j.optJSONArray("outbox")
                    if (ob != null && autoReceive) {
                        for (i in 0 until ob.length()) {
                            val o = ob.getJSONObject(i)
                            val id = o.getString("id")
                            val fresh = synchronized(seen) {
                                seen.add(id).also { if (it) prefs.edit().putStringSet("seen", HashSet(seen)).apply() }
                            }
                            if (fresh) queueDownload(id, o.getString("name"), o.getLong("size"))
                        }
                    }
                } catch (_: Exception) {
                    if (++fails >= 3) connected = false
                }
            }
            if (connected && ++tick % 6 == 0) probeUsb()
            try { Thread.sleep(if (connected) 500 else 1000) } catch (_: InterruptedException) {}
        }
    }

    // ---- speed test: measures 1/2/4/8 parallel streams both ways and keeps the fastest ------------

    fun runBench() {
        if (!connected || !benching.compareAndSet(false, true)) return
        thread(isDaemon = true) {
            try {
                val h = host ?: throw IOException("PC not found")
                var best = 1
                var bestScore = 0.0
                val sb = StringBuilder()
                for (n in intArrayOf(1, 2, 4, 8)) {
                    benchMsg = "Testing $n stream${if (n > 1) "s" else ""}…"
                    val d = benchDir(h, n, false)
                    val u = benchDir(h, n, true)
                    sb.append("${n}× ↓%.0f ↑%.0f MB/s\n".format(d, u))
                    if (bestScore == 0.0 || d + u > bestScore * 1.05) { bestScore = d + u; best = n }
                }
                streams = best
                prefs.edit().putInt("streams2", best).apply()
                benchMsg = sb.toString() + "→ using $best stream${if (best > 1) "s" else ""}"
            } catch (ex: Exception) {
                benchMsg = "Speed test failed: ${ex.message}"
            } finally {
                benching.set(false)
            }
        }
    }

    private fun benchDir(h: String, n: Int, up: Boolean): Double {
        val total = AtomicLong()
        val stop = AtomicBoolean()
        val conns = CopyOnWriteArrayList<Conn>()
        val t0 = System.nanoTime()
        val ths = (0 until n).map {
            thread(isDaemon = true) {
                try {
                    val c = Conn(h, port)
                    conns.add(c)
                    val buf = ByteArray(BUF)
                    if (up) {
                        c.benchUp()
                        while (!stop.get()) { c.out.write(buf); total.addAndGet(buf.size.toLong()); upBytes.addAndGet(buf.size.toLong()) }
                    } else {
                        c.benchDown()
                        while (!stop.get()) {
                            val r = c.inp.read(buf)
                            if (r < 0) break
                            total.addAndGet(r.toLong()); downBytes.addAndGet(r.toLong())
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        Thread.sleep(1800)
        stop.set(true)
        val secs = (System.nanoTime() - t0) / 1e9
        conns.forEach { it.close() }
        ths.forEach { it.join(1000) }
        return total.get() / 1048576.0 / secs
    }

    // ---- phone -> PC ----------------------------------------------------------------------------

    fun enqueueSend(items: List<SendItem>): Boolean {
        if (host == null) {
            items.forEach { try { it.pfd.close() } catch (_: Exception) {} }
            return false
        }
        for (it in items) {
            val x = Xfer(UUID.randomUUID().toString().replace("-", ""), it.name, it.size, true)
            xfers.add(x)
            sendQ.execute { upload(x, it) }
        }
        return true
    }

    private fun upload(x: Xfer, item: SendItem) {
        x.start = System.currentTimeMillis()
        x.started = true
        try {
            val h = host ?: throw IOException("PC not found")
            waitConnected(6000)
            val ch = FileInputStream(item.pfd.fileDescriptor).channel
            val seekable = try { ch.position(ch.position()); true } catch (_: IOException) { false }
            val n = if (x.size == 0L) 1 else ((x.size + CHUNK - 1) / CHUNK).toInt()
            val par = if (seekable) minOf(streams * paths().size.coerceAtLeast(1), n) else 1
            val qn = URLEncoder.encode(x.name, "UTF-8")
            val next = AtomicInteger()
            val err = AtomicReference<Throwable>()
            val hs = paths().ifEmpty { listOf(h) }
            val ths = (0 until par).map { idx ->
                thread(isDaemon = true) {
                    var conn: Conn? = null
                    val buf = ByteArray(BUF)
                    try {
                        while (err.get() == null) {
                            val i = next.getAndIncrement()
                            if (i >= n) break
                            val off = i * CHUNK
                            val len = minOf(CHUNK, x.size - off)
                            var tries = 0
                            while (true) {
                                var counted = 0L
                                try {
                                    if (conn == null) conn = Conn(hs[idx % hs.size], port)
                                    conn.put("/up?id=${x.id}&name=$qn&size=${x.size}&off=$off", len) { out ->
                                        var p = off
                                        var rem = len
                                        val bb = ByteBuffer.wrap(buf)
                                        while (rem > 0) {
                                            bb.clear()
                                            bb.limit(minOf(buf.size.toLong(), rem).toInt())
                                            val r = if (seekable) ch.read(bb, p) else ch.read(bb)
                                            if (r <= 0) throw EOFException("source ended")
                                            out.write(buf, 0, r)
                                            p += r; rem -= r; counted += r
                                            x.done.addAndGet(r.toLong())
                                            upBytes.addAndGet(r.toLong())
                                        }
                                    }
                                    break
                                } catch (e: Exception) {
                                    x.done.addAndGet(-counted)
                                    conn?.close(); conn = null
                                    if (!seekable || ++tries >= 3) throw e
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        err.compareAndSet(null, e)
                    } finally {
                        conn?.close()
                    }
                }
            }
            ths.forEach { it.join() }
            err.get()?.let { throw it }
            x.end = System.currentTimeMillis()
        } catch (e: Throwable) {
            x.error = friendly(e)
        } finally {
            try { item.pfd.close() } catch (_: Exception) {}
        }
    }

    // ---- PC -> phone ----------------------------------------------------------------------------

    private fun queueDownload(id: String, name: String, size: Long) {
        val x = Xfer(id, name, size, false)
        xfers.add(x)
        recvQ.execute { download(x) }
    }

    private fun download(x: Xfer) {
        x.start = System.currentTimeMillis()
        x.started = true
        val cr = ctx.contentResolver
        var uri: Uri? = null
        try {
            val h = host ?: throw IOException("PC not found")
            val ext = x.name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, x.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FastDrop")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("cannot create file")
            uri = u
            if (x.size > 0) {
                val n = ((x.size + CHUNK - 1) / CHUNK).toInt()
                val next = AtomicInteger()
                val err = AtomicReference<Throwable>()
                val hs = paths().ifEmpty { listOf(h) }
                val ths = (0 until minOf(streams * hs.size, n)).map { idx ->
                    thread(isDaemon = true) {
                        var conn: Conn? = null
                        val buf = ByteArray(BUF)
                        try {
                            (cr.openFileDescriptor(u, "rw") ?: throw IOException("cannot open file")).use { pfd ->
                                val ch = FileOutputStream(pfd.fileDescriptor).channel
                                while (err.get() == null) {
                                    val i = next.getAndIncrement()
                                    if (i >= n) break
                                    val off = i * CHUNK
                                    val len = minOf(CHUNK, x.size - off)
                                    var tries = 0
                                    while (true) {
                                        var counted = 0L
                                        try {
                                            if (conn == null) conn = Conn(hs[idx % hs.size], port)
                                            val r = conn.get("/file/${x.id}", off, off + len - 1)
                                            if (r.length != len) throw IOException("bad length from PC")
                                            var pos = off
                                            conn.readBody(r, buf) { got ->
                                                val bb = ByteBuffer.wrap(buf, 0, got)
                                                while (bb.hasRemaining()) pos += ch.write(bb, pos)
                                                counted += got
                                                x.done.addAndGet(got.toLong())
                                                downBytes.addAndGet(got.toLong())
                                            }
                                            break
                                        } catch (e: Exception) {
                                            x.done.addAndGet(-counted)
                                            conn?.close(); conn = null
                                            if (++tries >= 3) throw e
                                        }
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            err.compareAndSet(null, e)
                        } finally {
                            conn?.close()
                        }
                    }
                }
                ths.forEach { it.join() }
                err.get()?.let { throw it }
            }
            cr.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            x.uri = u
            x.end = System.currentTimeMillis()
        } catch (e: Throwable) {
            x.error = friendly(e)
            uri?.let { try { cr.delete(it, null, null) } catch (_: Exception) {} }
        }
    }

    companion object {
        fun openItems(cr: ContentResolver, uris: List<Uri>): List<SendItem> {
            val out = ArrayList<SendItem>()
            for (u in uris) {
                try {
                    val pfd = cr.openFileDescriptor(u, "r") ?: continue
                    var name: String? = null
                    var qsize = -1L
                    cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst()) {
                            name = it.getString(0)
                            if (!it.isNull(1)) qsize = it.getLong(1)
                        }
                    }
                    var size = pfd.statSize
                    if (size < 0) size = qsize
                    if (size < 0) { pfd.close(); continue }
                    out.add(SendItem(name ?: u.lastPathSegment ?: "file", size, pfd))
                } catch (_: Exception) {}
            }
            return out
        }
    }
}
