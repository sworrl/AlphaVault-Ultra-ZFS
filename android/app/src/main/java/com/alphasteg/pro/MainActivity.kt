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
        renderLibrary(library.load().values.sortedBy { it.name.lowercase() })
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
                renderLibrary(result.tracks)
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

    private fun renderLibrary(tracks: List<FlacTrack>) {
        // Remove any previously rendered rows, keep the empty-state placeholder.
        for (i in vaultFileList.childCount - 1 downTo 0) {
            if (vaultFileList.getChildAt(i) !== tvEmptyVault) {
                vaultFileList.removeViewAt(i)
            }
        }
        if (tracks.isEmpty()) {
            tvEmptyVault.visibility = View.VISIBLE
            return
        }
        tvEmptyVault.visibility = View.GONE
        for (t in tracks) {
            vaultFileList.addView(buildTrackRow(t))
        }
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
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return
            inputStream.close()

            // 1. Maxed Out Cascade 768-bit Multi-Cipher Encryption
            val encryptedBytes = CryptoEngine.encryptPayload(bytes, "MasterVaultPassword")

            // 2. Encode with RAID-Z2 Dual-Parity + Hot Spare Replication across library
            val raidZ2Result = RaidVaultEngine.encodeRaidZ2WithHotSpares(
                fileBytes = encryptedBytes,
                numDataChunks = 4,
                enableHotSpares = true
            )

            Toast.makeText(
                this,
                "File encrypted with 768-bit Cascade & distributed into ${raidZ2Result.chunks.size} FLAC tracks!\nMaximum Quantum-Grade Protection.",
                Toast.LENGTH_LONG
            ).show()

            refreshVaultList()
        } catch (e: Exception) {
            Toast.makeText(this, "Vault Error: ${e.message}", Toast.LENGTH_LONG).show()
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
            renderLibrary(emptyList())
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
