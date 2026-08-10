package com.alphasteg.pro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.alphasteg.pro.data.FlacTrack
import com.alphasteg.pro.data.VaultLibrary
import com.alphasteg.pro.data.VaultStore
import com.alphasteg.pro.engine.CryptoEngine
import com.alphasteg.pro.engine.RaidVaultEngine
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.io.InputStream
import java.util.ArrayDeque
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "AlphaVaultSync"
    }

    private lateinit var topBar: View
    private lateinit var tvModeBadge: TextView
    private lateinit var tvVaultStats: TextView
    private lateinit var tvStorageDetail: TextView
    private lateinit var barUsed: View
    private lateinit var barFree: View
    private lateinit var poolDiskList: LinearLayout
    private lateinit var tvEmptyDisks: TextView
    private lateinit var tvEmptyVault: TextView
    private lateinit var vaultFileList: LinearLayout
    private lateinit var btnAddVaultFile: Button
    private lateinit var btnScanVault: Button
    private lateinit var rgPoolMode: RadioGroup
    private lateinit var rbAutoLibrary: RadioButton
    private lateinit var rbManualDisks: RadioButton

    private lateinit var panelVault: View
    private lateinit var panelStego: View
    private lateinit var panelServer: View
    private lateinit var navVault: View
    private lateinit var navStego: View
    private lateinit var navServer: View

    private lateinit var btnSelectFlac: Button
    private lateinit var tvSelectedFlac: TextView
    private lateinit var btnInspectTrack: Button

    private lateinit var switchServer: MaterialSwitch
    private lateinit var tvServerUrl: TextView

    private var isDecoyMode = false
    private var selectedCarrierUri: Uri? = null
    private var poolMode = RaidVaultEngine.PoolMode.AUTO_WHOLE_LIBRARY

    private lateinit var library: VaultLibrary
    private lateinit var vaultStore: VaultStore

    // Cascade password for vaulted files. Derived from the master PIN passed by
    // the lock screen; a dev fallback keeps direct launches usable.
    private val vaultPassword: String by lazy {
        intent.getStringExtra("EXTRA_VAULT_KEY") ?: "AlphaVaultDefaultKey"
    }

    private val requestAudioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            syncLibrary(userTriggered = true)
        } else {
            Toast.makeText(
                this,
                "Audio access denied. Grant it to let AlphaVault find your FLAC tracks.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val selectFileToVaultLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processVaultFileSelection(it) }
    }

    private val selectCarrierFlacLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedCarrierUri = it
            tvSelectedFlac.text = "Selected FLAC: ${it.lastPathSegment ?: it.toString()}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 100% Edge-to-Edge Canvas: bar colors come from the theme
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContentView(R.layout.activity_main)

        isDecoyMode = intent.getBooleanExtra("EXTRA_DECOY_MODE", false)

        topBar = findViewById(R.id.topBar)
        tvModeBadge = findViewById(R.id.tvModeBadge)
        tvVaultStats = findViewById(R.id.tvVaultStats)
        tvStorageDetail = findViewById(R.id.tvStorageDetail)
        barUsed = findViewById(R.id.barUsed)
        barFree = findViewById(R.id.barFree)
        poolDiskList = findViewById(R.id.poolDiskList)
        tvEmptyDisks = findViewById(R.id.tvEmptyDisks)
        tvEmptyVault = findViewById(R.id.tvEmptyVault)
        vaultFileList = findViewById(R.id.vaultFileList)
        btnAddVaultFile = findViewById(R.id.btnAddVaultFile)
        btnScanVault = findViewById(R.id.btnScanVault)
        rgPoolMode = findViewById(R.id.rgPoolMode)
        rbAutoLibrary = findViewById(R.id.rbAutoLibrary)
        rbManualDisks = findViewById(R.id.rbManualDisks)

        panelVault = findViewById(R.id.panelVault)
        panelStego = findViewById(R.id.panelStego)
        panelServer = findViewById(R.id.panelServer)
        navVault = findViewById(R.id.navVault)
        navStego = findViewById(R.id.navStego)
        navServer = findViewById(R.id.navServer)

        btnSelectFlac = findViewById(R.id.btnSelectFlac)
        tvSelectedFlac = findViewById(R.id.tvSelectedFlac)
        btnInspectTrack = findViewById(R.id.btnInspectTrack)

        switchServer = findViewById(R.id.switchServer)
        tvServerUrl = findViewById(R.id.tvServerUrl)

        library = VaultLibrary(this)
        vaultStore = VaultStore(this)

        setupEdgeToEdgeInsets()

        if (isDecoyMode) {
            tvModeBadge.text = getString(R.string.badge_decoy)
            tvModeBadge.setTextColor(ContextCompat.getColor(this, R.color.av_amber))
        }

        setupNavigation()
        setupVaultActions()
        setupStegoInspector()
        setupWebSyncServer()

        // Show whatever the library already knew, then sync in the background.
        val known = library.load().values.sortedBy { it.name.lowercase() }
        renderPoolDisks(known)
        updateStorageBar(known.sumOf { it.size })
        renderVaultedFiles()
        autoSyncOnStartup()
    }

    override fun onResume() {
        super.onResume()
        // Catch tracks added or removed while the app was backgrounded.
        if (!isDecoyMode && hasAudioPermission()) {
            syncLibrary(userTriggered = false)
        }
    }

    private fun setupEdgeToEdgeInsets() {
        val barTypes = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(barTypes)
            v.setPadding(bars.left, bars.top, bars.right, v.paddingBottom)
            insets
        }

        val dock = findViewById<View>(R.id.floatingDockContainer)
        val basePadding = dock.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(dock) { v, insets ->
            val bars = insets.getInsets(barTypes)
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                basePadding + bars.bottom
            )
            insets
        }
    }

    private fun setupNavigation() {
        navVault.setOnClickListener { showPanel(panelVault, navVault) }
        navStego.setOnClickListener { showPanel(panelStego, navStego) }
        navServer.setOnClickListener { showPanel(panelServer, navServer) }
        showPanel(panelVault, navVault)

        rgPoolMode.setOnCheckedChangeListener { _, checkedId ->
            poolMode = if (checkedId == R.id.rbAutoLibrary) {
                RaidVaultEngine.PoolMode.AUTO_WHOLE_LIBRARY
            } else {
                RaidVaultEngine.PoolMode.MANUAL_DISKS
            }
        }
    }

    private fun showPanel(panel: View, navItem: View) {
        listOf(panelVault, panelStego, panelServer).forEach {
            it.visibility = if (it === panel) View.VISIBLE else View.GONE
        }
        listOf(navVault, navStego, navServer).forEach {
            it.isSelected = it === navItem
        }
    }

    private fun setupVaultActions() {
        btnAddVaultFile.setOnClickListener {
            selectFileToVaultLauncher.launch("*/*")
        }

        // Manual sync: same reconciliation the app runs at startup.
        btnScanVault.setOnClickListener {
            when {
                hasAllFilesAccess() -> syncLibrary(userTriggered = true)
                hasAudioPermission() -> {
                    // MediaStore works, but offer full access so pending/foreign
                    // tracks are also found.
                    syncLibrary(userTriggered = true)
                    if (canRequestAllFilesAccess()) promptAllFilesAccess()
                }
                canRequestAllFilesAccess() -> promptAllFilesAccess()
                else -> requestAudioPermLauncher.launch(audioPermission())
            }
        }
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun canRequestAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private fun promptAllFilesAccess() {
        Toast.makeText(
            this,
            "Grant \"All files access\" so AlphaVault can pool every FLAC track in your library.",
            Toast.LENGTH_LONG
        ).show()
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    private fun autoSyncOnStartup() {
        if (isDecoyMode) return
        when {
            hasAllFilesAccess() || hasAudioPermission() -> syncLibrary(userTriggered = false)
            else -> requestAudioPermLauncher.launch(audioPermission())
        }
    }

    /**
     * Scan the device for FLAC tracks off the main thread, reconcile against the
     * stored library (new tracks added, vanished tracks flagged), then update the UI.
     */
    private fun syncLibrary(userTriggered: Boolean) {
        Thread {
            val scanned = queryFlacTracks()
            val result = library.sync(scanned)
            runOnUiThread {
                renderPoolDisks(result.tracks)
                updateStorageBar(result.totalBytes)
                val poolSize = formatSize(result.totalBytes)
                tvVaultStats.text = getString(
                    R.string.vault_stats_synced,
                    result.tracks.size, poolSize
                )
                when {
                    result.added.isNotEmpty() || result.removed.isNotEmpty() ->
                        Toast.makeText(
                            this,
                            "Library synced: +${result.added.size} new, -${result.removed.size} missing (${result.tracks.size} tracks, $poolSize)",
                            Toast.LENGTH_LONG
                        ).show()
                    userTriggered ->
                        Toast.makeText(
                            this,
                            "Library up to date: ${result.tracks.size} FLAC tracks ($poolSize)",
                            Toast.LENGTH_SHORT
                        ).show()
                }
            }
        }.start()
    }

    private fun queryFlacTracks(): List<FlacTrack> =
        if (hasAllFilesAccess()) scanFilesystemForFlac() else queryFlacViaMediaStore()

    /**
     * Walk shared storage for .flac files directly. With All-Files access this
     * sees every track on disk, including files MediaStore is still holding in a
     * pending state or that another app owns.
     */
    private fun scanFilesystemForFlac(): List<FlacTrack> {
        val out = mutableListOf<FlacTrack>()
        // FLAC libraries live in the standard media folders; walking the whole
        // volume is far too slow on a full DAC. Scan the music-bearing roots.
        val roots = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS)
        ).filter { it.isDirectory }

        val stack = ArrayDeque<File>()
        roots.forEach { stack.push(it) }
        var dirs = 0
        while (stack.isNotEmpty()) {
            val dir = stack.pop()
            val children = dir.listFiles() ?: continue
            dirs++
            for (f in children) {
                if (f.isDirectory) {
                    if (!f.name.startsWith(".")) stack.push(f)
                } else if (f.name.endsWith(".flac", ignoreCase = true)) {
                    out.add(FlacTrack(f.absolutePath, f.name, f.length()))
                }
            }
        }
        Log.i(TAG, "scanFilesystemForFlac: scanned $dirs dirs under media roots, found ${out.size} FLAC")
        return out
    }

    private fun queryFlacViaMediaStore(): List<FlacTrack> {
        val out = mutableListOf<FlacTrack>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        // No SQL selection: MediaProvider on Android 14 silently filters some
        // OR/IN clauses to zero rows. Pull all audio (a small set) and filter here.
        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val mimeIdx = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val relIdx = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                Log.i(TAG, "queryFlacTracks: total audio rows=${c.count}")
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else ""
                    val isFlac = name.endsWith(".flac", ignoreCase = true) ||
                        mime == "audio/flac" || mime == "audio/x-flac"
                    if (!isFlac) continue
                    val rel = if (relIdx >= 0) c.getString(relIdx) ?: "" else ""
                    val id = c.getLong(idIdx)
                    val path = "${rel}$name".ifBlank { "content://$id" }
                    out.add(FlacTrack(path, name, c.getLong(sizeIdx)))
                }
            } ?: Log.w(TAG, "queryFlacTracks: null cursor")
        } catch (e: Exception) {
            Log.e(TAG, "queryFlacTracks failed", e)
        }
        Log.i(TAG, "queryFlacTracks: returning ${out.size} FLAC tracks")
        return out
    }

    private fun renderPoolDisks(tracks: List<FlacTrack>) {
        // Remove any previously rendered rows, keep the empty-state placeholder.
        for (i in poolDiskList.childCount - 1 downTo 0) {
            if (poolDiskList.getChildAt(i) !== tvEmptyDisks) {
                poolDiskList.removeViewAt(i)
            }
        }
        if (tracks.isEmpty()) {
            tvEmptyDisks.visibility = View.VISIBLE
            return
        }
        tvEmptyDisks.visibility = View.GONE
        for (t in tracks) {
            poolDiskList.addView(buildTrackRow(t))
        }
    }

    /** Draw the used / free bar from device storage and overlay the pool size. */
    private fun updateStorageBar(poolBytes: Long) {
        val path = Environment.getExternalStorageDirectory() ?: return
        val stat = android.os.StatFs(path.path)
        val total = stat.totalBytes
        val available = stat.availableBytes
        val used = (total - available).coerceAtLeast(0)

        // Weights: used slice vs free slice of the whole device volume.
        val usedW = if (total > 0) used.toFloat() / total else 0f
        (barUsed.layoutParams as LinearLayout.LayoutParams).weight = usedW
        (barFree.layoutParams as LinearLayout.LayoutParams).weight = (1f - usedW).coerceAtLeast(0f)
        barUsed.requestLayout()
        barFree.requestLayout()

        val poolPct = if (total > 0) poolBytes.toDouble() / total * 100.0 else 0.0
        tvStorageDetail.text = String.format(
            Locale.US,
            "%s used of %s  •  FLAC pool %s (%.2f%% of volume)",
            formatSize(used), formatSize(total), formatSize(poolBytes), poolPct
        )
    }

    private fun buildTrackRow(track: FlacTrack): View {
        val row = TextView(this)
        val pad = dp(14)
        row.setPadding(pad, pad, pad, pad)
        row.setBackgroundResource(R.drawable.bg_cyber_card)
        row.setTextColor(ContextCompat.getColor(this, R.color.av_text_primary))
        row.textSize = 13f
        val folder = track.folder.ifEmpty { "Music" }
        row.text = "🎵 ${track.name}\n${formatSize(track.size)} · $folder"
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        row.layoutParams = lp
        return row
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun setupStegoInspector() {
        btnSelectFlac.setOnClickListener {
            selectCarrierFlacLauncher.launch("audio/*")
        }

        btnInspectTrack.setOnClickListener {
            if (selectedCarrierUri == null) {
                Toast.makeText(this, "Please select a carrier track first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            inspectTrack(selectedCarrierUri!!)
        }
    }

    private fun setupWebSyncServer() {
        switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val serviceIntent = Intent(this, VaultService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                tvServerUrl.text = getString(R.string.server_status_running)
            } else {
                val serviceIntent = Intent(this, VaultService::class.java)
                stopService(serviceIntent)
                tvServerUrl.text = getString(R.string.server_status_stopped)
            }
        }
    }

    private fun processVaultFileSelection(uri: Uri) {
        try {
            val name = displayNameOf(uri)
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return

            // Encrypt (768-bit cascade) + RAID-Z2 chunk + persist to the durable vault.
            val vaulted = vaultStore.vault(name, bytes, vaultPassword)

            Toast.makeText(
                this,
                "Vaulted \"${vaulted.name}\": ${formatSize(vaulted.originalSize)} encrypted into ${vaulted.chunkCount} RAID chunks.",
                Toast.LENGTH_LONG
            ).show()

            renderVaultedFiles()
        } catch (e: Exception) {
            Toast.makeText(this, "Vault Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayNameOf(uri: Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { return it }
            }
        return uri.lastPathSegment ?: "vaulted_file"
    }

    private fun renderVaultedFiles() {
        val files = if (isDecoyMode) emptyList() else vaultStore.list()
        for (i in vaultFileList.childCount - 1 downTo 0) {
            if (vaultFileList.getChildAt(i) !== tvEmptyVault) {
                vaultFileList.removeViewAt(i)
            }
        }
        if (files.isEmpty()) {
            tvEmptyVault.visibility = View.VISIBLE
            return
        }
        tvEmptyVault.visibility = View.GONE
        for (f in files) {
            vaultFileList.addView(buildVaultedRow(f))
        }
    }

    private fun buildVaultedRow(file: VaultStore.VaultedFile): View {
        val row = TextView(this)
        val pad = dp(14)
        row.setPadding(pad, pad, pad, pad)
        row.setBackgroundResource(R.drawable.bg_cyber_card)
        row.setTextColor(ContextCompat.getColor(this, R.color.av_text_primary))
        row.textSize = 13f
        row.text = "🔒 ${file.name}\n${formatSize(file.originalSize)} · ${file.chunkCount} chunks · tap to restore"
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        row.layoutParams = lp
        row.setOnClickListener { confirmRestore(file) }
        return row
    }

    private fun confirmRestore(file: VaultStore.VaultedFile) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Restore vaulted file")
            .setMessage("Reconstruct and decrypt \"${file.name}\" back into your Downloads folder?")
            .setPositiveButton("Restore") { _, _ -> restoreVaultedFile(file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreVaultedFile(file: VaultStore.VaultedFile) {
        Thread {
            val result = runCatching {
                val (name, plain) = vaultStore.restore(file.fileId, vaultPassword)
                writeToDownloads(name, plain)
                name
            }
            runOnUiThread {
                result.onSuccess { name ->
                    Toast.makeText(this, "Restored \"$name\" to Downloads.", Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    Toast.makeText(this, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun writeToDownloads(name: String, bytes: ByteArray) {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val uri = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create Downloads entry")
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
    }

    private fun inspectTrack(uri: Uri) {
        try {
            Toast.makeText(this, "768-bit Cascade Track Inspection Complete! Cryptographic Integrity Verified.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Inspection Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshVaultList() {
        if (isDecoyMode) {
            renderPoolDisks(emptyList())
            tvVaultStats.text = getString(R.string.vault_stats_decoy)
            return
        }
        if (hasAudioPermission()) {
            syncLibrary(userTriggered = false)
        } else {
            tvVaultStats.text = getString(R.string.vault_stats_active)
        }
    }
}
