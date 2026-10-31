package com.devaprakash.fastdrop

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val bg = Color.parseColor("#0E1116")
    private val panel = Color.parseColor("#161B22")
    private val line = Color.parseColor("#262D38")
    private val fg = Color.parseColor("#E6EDF3")
    private val mute = Color.parseColor("#8B96A5")
    private val acc = Color.parseColor("#3DDC97")
    private val acc2 = Color.parseColor("#4CC2FF")
    private val warn = Color.parseColor("#FFC857")
    private val bad = Color.parseColor("#FF6B6B")

    private class Row(
        val root: LinearLayout, val title: TextView, val pct: TextView, val bar: ProgressBar, val meta: TextView,
        val folder: TextView
    )

    private lateinit var e: Engine
    private lateinit var gauge: SpeedView
    private lateinit var status: TextView
    private lateinit var sub: TextView
    private lateinit var wifiPill: TextView
    private lateinit var cablePill: TextView
    private lateinit var hint: TextView
    private val modeBtns = HashMap<LinkMode, Button>()
    private lateinit var hostEt: EditText
    private lateinit var benchTv: TextView
    private lateinit var sumCard: LinearLayout
    private lateinit var sumTitle: TextView
    private lateinit var sumPct: TextView
    private lateinit var sumBar: ProgressBar
    private lateinit var sumMeta: TextView
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private val rows = HashMap<Xfer, Row>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastUp = 0L
    private var lastDown = 0L
    private var lastT = 0L
    private var rate = 0f

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(fill: Int, r: Int = 12, stroke: Int = 0, sw: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(sw), stroke)
    }

    private fun btn(label: String, primary: Boolean) = Button(this).apply {
        this.text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(if (primary) Color.parseColor("#06251A") else fg)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(if (primary) acc else panel, 12, if (primary) 0 else line)
        stateListAnimator = null
    }

    private fun bar(): ProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(acc)
        progressBackgroundTintList = ColorStateList.valueOf(line)
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        e = (application as App).engine
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("Fast", 28f, fg, true))
        head.addView(tv("Drop", 28f, acc, true))
        col.addView(head)
        gauge = SpeedView(this)
        col.addView(gauge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)).apply { topMargin = dp(8) })

        // ---- connection card: status, Wi-Fi / Cable pills, mode selector ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 14, line)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        status = tv("", 17f, fg, true)
        sub = tv("", 13f, mute)
        card.addView(status)
        card.addView(sub)
        val pills = LinearLayout(this)
        wifiPill = tv("", 13f, mute, true).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        cablePill = tv("", 13f, mute, true).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        pills.addView(wifiPill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        pills.addView(cablePill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        card.addView(pills, lp(top = 12))
        val seg = LinearLayout(this)
        for ((m, label) in listOf(LinkMode.BOTH to "Wi-Fi + Cable", LinkMode.WIFI to "Wi-Fi only", LinkMode.CABLE to "Cable only")) {
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                stateListAnimator = null
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { e.mode = m }
            }
            modeBtns[m] = b
            seg.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        }
        card.addView(seg, lp(top = 10))
        hint = tv("", 12f, mute)
        card.addView(hint, lp(top = 8))
        col.addView(card, lp(top = 16))

        val scan = btn("📷  Scan QR to connect", false).apply {
            background = rounded(Color.TRANSPARENT, 12, acc, 2)
            setTextColor(acc)
            setOnClickListener { scanQr() }
        }
        col.addView(scan, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })

        val addr = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        hostEt = EditText(this).apply {
            hint = "PC address (optional, e.g. 192.168.1.7)"
            setHintTextColor(mute)
            setTextColor(fg)
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(panel, 10, line)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val go = btn("Connect", false).apply {
            textSize = 14f
            setOnClickListener {
                e.setManualHost(hostEt.text.toString())
                Toast.makeText(this@MainActivity, "Connecting…", Toast.LENGTH_SHORT).show()
            }
        }
        addr.addView(hostEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addr.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(8) })
        col.addView(addr, lp(top = 12))

        val send = btn("Send files to PC", true).apply { setOnClickListener { pickFiles() } }
        col.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(16) })

        val test = btn("Speed test", false).apply {
            textSize = 14f
            setOnClickListener {
                if (!e.connected) Toast.makeText(this@MainActivity, "Connect to the PC first", Toast.LENGTH_SHORT).show()
                else e.runBench()
            }
        }
        col.addView(test, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        benchTv = tv("", 12f, mute)
        col.addView(benchTv, lp(top = 8))

        val sw = Switch(this).apply {
            this.text = "Auto-receive files dropped on the PC"
            setTextColor(fg)
            isChecked = e.autoReceive
            setOnCheckedChangeListener { _, v -> e.autoReceive = v }
        }
        col.addView(sw, lp(top = 16))

        // ---- overall progress for the whole batch ----
        sumCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 14, acc, 1)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        val sumTop = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sumTitle = tv("", 15f, fg, true)
        sumPct = tv("", 22f, acc, true)
        sumTop.addView(sumTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sumTop.addView(sumPct)
        sumBar = bar()
        sumMeta = tv("", 12f, mute)
        sumCard.addView(sumTop)
        sumCard.addView(sumBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(8) })
        sumCard.addView(sumMeta, lp(top = 6))
        col.addView(sumCard, lp(top = 18))

        val h2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        h2.addView(tv("FILES", 12f, mute, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        h2.addView(tv("clear", 13f, mute).apply { setOnClickListener { e.clearDone() } })
        col.addView(h2, lp(top = 18))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        empty = tv("Nothing yet. Pick files, or share to FastDrop from any app.", 14f, mute)
        col.addView(list, lp(top = 6))
        col.addView(empty, lp(top = 16))

        val folder = btn("Open received files in file manager", false).apply {
            textSize = 14f
            setOnClickListener { openReceivedFolder() }
        }
        col.addView(folder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(20) })

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(col) })
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme == "fastdrop") connectFromLink(d)
    }

    private fun connectFromLink(d: Uri) {
        e.start()
        e.connectFromQr(d) { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
    }

    private fun scanQr() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { b ->
                val raw = b.rawValue ?: ""
                if (raw.startsWith("fastdrop://")) connectFromLink(Uri.parse(raw))
                else Toast.makeText(this, "That's not a FastDrop QR code", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Scanner unavailable (${it.message}). Type the PC address instead.", Toast.LENGTH_LONG).show()
            }
    }

    /** Opens Download/FastDrop in the system file manager (falls back to the Downloads list). */
    private fun openReceivedFolder() {
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/FastDrop")
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        // prefer the system Files app so no chooser pops up
        for (pkg in listOf("com.google.android.documentsui", "com.android.documentsui")) {
            try { startActivity(Intent(view).setPackage(pkg)); return } catch (_: Exception) {}
        }
        try { startActivity(view) } catch (_: Exception) {
            try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) {
                Toast.makeText(this, "No file manager found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        e.uiVisible = true
        startForegroundService(Intent(this, TransferService::class.java))
        e.start()
        handler.post(tick)
    }

    override fun onStop() {
        e.uiVisible = false
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 150)
        }
    }

    private fun fmt(b: Long) = when {
        b < 1024 -> "$b B"
        b < 1 shl 20 -> "%.0f KB".format(b / 1024.0)
        b < 1 shl 30 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    private fun eta(remaining: Long, mbps: Double): String {
        if (mbps < 0.3 || remaining <= 0) return ""
        val s = remaining / 1048576.0 / mbps
        return when {
            s < 1 -> " · <1s left"
            s < 90 -> " · ${s.toInt()}s left"
            else -> " · ${(s / 60).toInt()}m left"
        }
    }

    private fun pill(v: TextView, label: String, on: Boolean, amber: Boolean = false) {
        val c = if (on) acc else if (amber) warn else mute
        v.text = "● $label"
        v.setTextColor(c)
        v.background = rounded(Color.TRANSPARENT, 10, if (on) acc else line)
    }

    private fun setProgress(b: ProgressBar, v: Int) = b.setProgress(v.coerceIn(0, 1000), true)

    private fun refresh() {
        val h = e.host
        val name = e.pcName.ifEmpty { "PC" }
        when {
            e.connected -> {
                status.text = "Connected to $name"
                status.setTextColor(acc)
                sub.text = "$h:${e.port}"
            }
            h != null -> {
                status.text = "Looking for $name…"
                status.setTextColor(warn)
                sub.text = "Last known $h — open FastDrop on the PC (same Wi-Fi)"
            }
            else -> {
                status.text = "Searching for your PC…"
                status.setTextColor(mute)
                sub.text = "Open FastDrop on the PC. Same Wi-Fi required."
            }
        }
        pill(wifiPill, if (e.connected) "Wi-Fi connected" else if (h != null) "Wi-Fi looking…" else "Wi-Fi searching", e.connected, h != null)
        pill(cablePill, if (e.usbUp) "Cable active" else "Cable not detected", e.usbUp)
        for ((m, b) in modeBtns) {
            val on = e.mode == m
            b.setTextColor(if (on) Color.parseColor("#06251A") else fg)
            b.background = rounded(if (on) acc else panel, 10, if (on) 0 else line)
        }
        hint.text = when {
            !e.connected -> "Waiting for the PC…"
            e.mode == LinkMode.CABLE && !e.usbUp ->
                "Cable only is selected but no cable is detected, so Wi-Fi is used. Plug in USB, turn on USB debugging, and run FastDrop on the PC with adb."
            e.mode == LinkMode.WIFI -> "Sending over Wi-Fi only."
            e.mode == LinkMode.CABLE -> "Sending over the USB cable only."
            e.usbUp -> "Using Wi-Fi and the cable together for maximum speed."
            else -> "Plug in a USB cable (USB debugging on) to add cable speed."
        }

        val now = System.currentTimeMillis()
        val up = e.upBytes.get()
        val dn = e.downBytes.get()
        if (lastT != 0L && now > lastT) {
            val dt = (now - lastT) / 1000f
            val u = (up - lastUp) / 1048576f / dt
            val d = (dn - lastDown) / 1048576f / dt
            gauge.update(u, d)
            rate += ((u + d) - rate) * 0.25f
        }
        lastUp = up; lastDown = dn; lastT = now
        benchTv.text = if (e.benchMsg.isEmpty()) "Speed test picks the best stream count (now ${e.streams} per link)" else e.benchMsg

        val cur = e.xfers.toList()
        rows.keys.filter { it !in cur }.forEach { list.removeView(rows.remove(it)!!.root) }
        for (x in cur) {
            val r = rows.getOrPut(x) { makeRow(x).also { list.addView(it.root, 0) } }
            val d = x.done.get().coerceAtMost(x.size)
            val p = if (x.size > 0) (d * 1000 / x.size).toInt() else if (x.end != 0L) 1000 else 0
            setProgress(r.bar, if (x.end != 0L) 1000 else p)
            val secs = ((if (x.end != 0L) x.end else now) - x.start) / 1000.0
            val mbps = if (secs > 0.2 && d > 0) d / 1048576.0 / secs else 0.0
            val sp = if (mbps > 0) " · %.0f MB/s".format(mbps) else ""
            val arrow = if (x.up) "↑ to PC" else "↓ from PC"
            when {
                x.error != null -> {
                    r.pct.text = "✕"; r.pct.setTextColor(bad)
                    r.meta.text = x.error; r.meta.setTextColor(bad)
                    r.bar.progressTintList = ColorStateList.valueOf(bad)
                }
                x.end != 0L -> {
                    r.folder.visibility = if (x.up) View.GONE else View.VISIBLE
                    r.pct.text = "100%"; r.pct.setTextColor(acc)
                    r.meta.text = "$arrow · ${fmt(x.size)} · done$sp"; r.meta.setTextColor(acc)
                }
                !x.started -> {
                    r.pct.text = "0%"; r.pct.setTextColor(mute)
                    r.meta.text = "$arrow · ${fmt(x.size)} · waiting in queue"; r.meta.setTextColor(mute)
                }
                else -> {
                    r.pct.text = "${p / 10}%"; r.pct.setTextColor(if (x.up) acc2 else acc)
                    r.meta.text = "$arrow · ${fmt(d)} / ${fmt(x.size)}$sp" + eta(x.size - d, mbps); r.meta.setTextColor(mute)
                }
            }
        }
        empty.visibility = if (cur.isEmpty()) View.VISIBLE else View.GONE

        // overall batch card (shown for any multi-file or in-flight transfer)
        val showSum = cur.size > 1 || cur.any { it.active }
        sumCard.visibility = if (showSum && cur.isNotEmpty()) View.VISIBLE else View.GONE
        if (cur.isNotEmpty()) {
            val total = cur.sumOf { it.size }
            val got = cur.sumOf { if (it.end != 0L) it.size else it.done.get().coerceAtMost(it.size) }
            val done = cur.count { it.end != 0L }
            val failed = cur.count { it.error != null }
            val p = if (total > 0) (got * 1000 / total).toInt() else 0
            setProgress(sumBar, p)
            sumPct.text = "${p / 10}%"
            sumTitle.text = "$done of ${cur.size} file${if (cur.size > 1) "s" else ""}" + if (failed > 0) " · $failed failed" else ""
            sumMeta.text = "${fmt(got)} / ${fmt(total)}" + (if (rate > 0.3f) " · %.0f MB/s".format(rate) else "") +
                eta(total - got, rate.toDouble())
        }
    }

    private fun makeRow(x: Xfer): Row {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 10, line)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = lp(6)
            setOnClickListener {
                val u = x.uri ?: return@setOnClickListener
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "No app to open this file", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = tv(x.name, 15f, fg).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val pct = tv("0%", 15f, mute, true)
        val folder = tv("📁", 18f, fg).apply {
            visibility = View.GONE
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { openReceivedFolder() }
        }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(folder)
        top.addView(pct)
        val b = bar().apply { progressTintList = ColorStateList.valueOf(if (x.up) acc2 else acc) }
        val meta = tv("", 12f, mute)
        root.addView(top)
        root.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
        root.addView(meta, lp(top = 4))
        return Row(root, title, pct, b, meta, folder)
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val c: ClipData? = data.clipData
        if (c != null) for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) else data.data?.let { uris.add(it) }
        thread {
            val items = Engine.openItems(contentResolver, uris)
            runOnUiThread {
                if (items.isEmpty() || !e.enqueueSend(items)) {
                    Toast.makeText(this, "PC not found yet — wait for “Connected”", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
