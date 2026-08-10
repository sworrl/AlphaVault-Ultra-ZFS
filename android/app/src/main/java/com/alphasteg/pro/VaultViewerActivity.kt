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
    private var pdfRenderer: android.graphics.pdf.PdfRenderer? = null
    private var pdfThread: android.os.HandlerThread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hardening: block screenshots, screen recording, and recents thumbnails.
        if (!BuildConfig.ALLOW_SCREENSHOTS) window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
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
            Kind.VIDEO -> root.addView(videoView(name, bytes))
            Kind.PDF -> root.addView(pdfView(bytes))
            Kind.OTHER -> root.addView(restoreOnlyView(name, bytes))
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
        // Decode down to screen size so a huge image never blows the canvas limit.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val dm = resources.displayMetrics
        val reqW = (dm.widthPixels * 2).coerceAtLeast(1)
        val reqH = (dm.heightPixels * 2).coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / sample > reqW || bounds.outHeight / sample > reqH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
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

    /** Video plays from an in-memory data source onto a secure surface, with a
     *  scrub bar and play/pause via MediaController (no disk, no other app). */
    private fun videoView(name: String, bytes: ByteArray): View {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return message("In-app video needs Android 6+.\nUse Restore to export it instead.")
        }
        val frame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        val surface = android.view.SurfaceView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER)
        }
        frame.addView(surface)

        val controller = android.widget.MediaController(this)
        surface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                runCatching {
                    val mp = MediaPlayer()
                    mp.setDataSource(ByteArrayMediaDataSource(bytes))
                    mp.setSurface(h.surface)
                    mp.setOnPreparedListener {
                        it.start()
                        val control = mediaControlFor(mp)
                        controller.setMediaPlayer(control)
                        controller.setAnchorView(frame)
                        controller.isEnabled = true
                        frame.setOnClickListener { controller.show() }
                        controller.show(0)
                    }
                    mp.prepareAsync()
                    player = mp
                }.onFailure {
                    Toast.makeText(this@VaultViewerActivity, "Cannot play this video: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
            override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: android.view.SurfaceHolder) { player?.release(); player = null }
        })
        return frame
    }

    /** Bridge a raw MediaPlayer to MediaController so scrubbing and play/pause work. */
    private fun mediaControlFor(mp: MediaPlayer) = object : android.widget.MediaController.MediaPlayerControl {
        override fun start() = mp.start()
        override fun pause() = mp.pause()
        override fun getDuration(): Int = runCatching { mp.duration }.getOrDefault(0)
        override fun getCurrentPosition(): Int = runCatching { mp.currentPosition }.getOrDefault(0)
        override fun seekTo(pos: Int) { mp.seekTo(pos) }
        override fun isPlaying(): Boolean = runCatching { mp.isPlaying }.getOrDefault(false)
        override fun getBufferPercentage(): Int = 0
        override fun canPause(): Boolean = true
        override fun canSeekBackward(): Boolean = true
        override fun canSeekForward(): Boolean = true
        override fun getAudioSessionId(): Int = runCatching { mp.audioSessionId }.getOrDefault(0)
    }

    /** PDF rendered page-by-page in-app via a seekable in-memory descriptor (no disk, no other app). */
    private fun pdfView(bytes: ByteArray): View {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return message("In-app PDF needs Android 8+.\nUse Restore to export it instead.")
        }
        return try {
            val sm = getSystemService(android.os.storage.StorageManager::class.java)
            pdfThread = android.os.HandlerThread("vault-pdf").apply { start() }
            val handler = android.os.Handler(pdfThread!!.looper)
            val pfd = sm.openProxyFileDescriptor(
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                object : android.os.ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = bytes.size.toLong()
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        if (offset >= bytes.size) return 0
                        val n = minOf(size.toLong(), bytes.size - offset).toInt()
                        System.arraycopy(bytes, offset.toInt(), data, 0, n)
                        return n
                    }
                    override fun onRelease() {}
                },
                handler
            )
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            pdfRenderer = renderer
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val width = resources.displayMetrics.widthPixels
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    val h = (width.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(width, h, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    column.addView(ImageView(this).apply {
                        setImageBitmap(bmp)
                        adjustViewBounds = true
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
                    })
                }
            }
            ScrollView(this).apply {
                addView(column)
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
        } catch (e: Exception) {
            message("Could not render this PDF (${e.message}).\nUse Restore to export it instead.")
        }
    }

    private fun restoreOnlyView(name: String, bytes: ByteArray): View =
        message("“$name” (${bytes.size} bytes)\nThis type has no secure in-app viewer.\nUse Restore from the vault to export it.")

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
        runCatching { pdfRenderer?.close() }
        pdfRenderer = null
        pdfThread?.quitSafely()
        pdfThread = null
    }

    private enum class Kind { IMAGE, TEXT, AUDIO, VIDEO, PDF, OTHER }

    private fun kindOf(name: String, bytes: ByteArray): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> Kind.IMAGE
            "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v" -> Kind.VIDEO
            "pdf" -> Kind.PDF
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
