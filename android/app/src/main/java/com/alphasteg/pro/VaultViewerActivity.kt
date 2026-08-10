package com.alphasteg.pro

import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Secure in-app viewer for a vaulted file. The decrypted bytes are handed over
 * in memory (never written to disk) and rendered by type: images, text, and
 * audio play back from RAM. Anything else shows its details and points the user
 * at Restore. The plaintext handle is cleared when the viewer closes.
 */
class VaultViewerActivity : AppCompatActivity() {

    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val name = intent.getStringExtra(EXTRA_NAME) ?: "Vaulted file"
        val bytes = pending
        pending = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050811"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        root.addView(header(name))

        if (bytes == null) {
            root.addView(message("This file is no longer available to view. Reopen it from the vault."))
            setContentView(root)
            return
        }

        when (kindOf(name, bytes)) {
            Kind.IMAGE -> root.addView(imageView(bytes))
            Kind.TEXT -> root.addView(textView(String(bytes, Charsets.UTF_8)))
            Kind.AUDIO -> root.addView(audioView(name, bytes))
            Kind.OTHER -> root.addView(
                message("In-app viewing isn't supported for this file type yet (${bytes.size} bytes).\nUse Restore to save it to Downloads.")
            )
        }
        setContentView(root)
    }

    private fun header(name: String): View = TextView(this).apply {
        text = "🔒  $name"
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }

    private fun message(msg: String): View = TextView(this).apply {
        text = msg
        setTextColor(Color.parseColor("#8F9CAE"))
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(40), dp(24), dp(24))
    }

    private fun imageView(bytes: ByteArray): View {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return message("Could not decode this image.")
        val scroll = ScrollView(this)
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        scroll.addView(iv)
        scroll.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        return scroll
    }

    private fun textView(text: String): View {
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(8), dp(20), dp(24))
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        scroll.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        return scroll
    }

    private fun audioView(name: String, bytes: ByteArray): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        val status = TextView(this).apply {
            text = "♪ $name\nDecrypted in memory."
            setTextColor(Color.parseColor("#00F2FE"))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val btn = Button(this).apply { text = "PLAY" }
        container.addView(status)
        container.addView(btn)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            status.text = "In-app audio playback needs Android 6+."
            btn.visibility = View.GONE
            return container
        }

        btn.setOnClickListener {
            val mp = player
            if (mp != null && mp.isPlaying) {
                mp.pause(); btn.text = "PLAY"; return@setOnClickListener
            }
            if (mp != null) { mp.start(); btn.text = "PAUSE"; return@setOnClickListener }
            runCatching {
                val fresh = MediaPlayer()
                fresh.setDataSource(ByteArrayMediaDataSource(bytes))
                fresh.setOnCompletionListener { btn.text = "PLAY" }
                fresh.setOnPreparedListener { it.start(); btn.text = "PAUSE" }
                fresh.prepareAsync()
                player = fresh
            }.onFailure {
                Toast.makeText(this, "Cannot play this audio: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
        return container
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private enum class Kind { IMAGE, TEXT, AUDIO, OTHER }

    private fun kindOf(name: String, bytes: ByteArray): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> Kind.IMAGE
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus" -> Kind.AUDIO
            "txt", "md", "markdown", "json", "csv", "log", "xml", "html", "htm",
            "kt", "java", "py", "js", "ts", "css", "yaml", "yml", "ini", "cfg", "conf", "sh" -> Kind.TEXT
            else -> if (looksLikeText(bytes)) Kind.TEXT else Kind.OTHER
        }
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.take(512)
        if (sample.isEmpty()) return false
        val control = sample.count { val b = it.toInt(); b == 0 || (b in 1..8) || b in 14..31 }
        return control == 0
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** In-memory data source so MediaPlayer never touches disk. */
    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val end = minOf(position + size, data.size.toLong()).toInt()
            val count = end - position.toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    companion object {
        private const val EXTRA_NAME = "vault_view_name"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        // Plaintext handed over in memory rather than through an Intent (which caps
        // at ~1 MB and would be logged). Cleared as soon as the viewer reads it.
        @Volatile
        private var pending: ByteArray? = null

        fun show(activity: AppCompatActivity, name: String, bytes: ByteArray) {
            pending = bytes
            activity.startActivity(
                android.content.Intent(activity, VaultViewerActivity::class.java)
                    .putExtra(EXTRA_NAME, name)
            )
        }
    }
}
