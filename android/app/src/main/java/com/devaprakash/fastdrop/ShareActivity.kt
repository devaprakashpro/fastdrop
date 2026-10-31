package com.devaprakash.fastdrop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/** Target of the Android share sheet: "Share → FastDrop" streams the files to the PC. */
class ShareActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        val e = (application as App).engine
        val items = Engine.openItems(contentResolver, uris)
        if (items.isEmpty()) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
        } else {
            startForegroundService(Intent(this, TransferService::class.java))
            e.start()
            if (e.enqueueSend(items)) {
                Toast.makeText(this, "Sending ${items.size} file(s) to PC…", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Open FastDrop once to find your PC first", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
