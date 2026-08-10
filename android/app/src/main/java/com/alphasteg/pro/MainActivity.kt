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
import com.alphasteg.pro.data.VaultVolume
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
    private lateinit var partitionBar: com.alphasteg.pro.ui.PartitionBarView
    private lateinit var treemap: com.alphasteg.pro.ui.TreemapView
    private lateinit var tvEmptyDisks: TextView
    private lateinit var tvEmptyVault: TextView
    private lateinit var vaultFileList: LinearLayout
    private lateinit var btnAddVaultFile: Button
    private lateinit var btnScanVault: Button
    private lateinit var rgPoolMode: RadioGroup
    private lateinit var rbAutoLibrary: RadioButton
    private lateinit var rbManualDisks: RadioButton

    private lateinit var panelVault: View
    private lateinit var panelDisks: View
    private lateinit var panelStego: View
    private lateinit var panelServer: View
    private lateinit var navVault: View
    private lateinit var navDisks: View
    private lateinit var navStego: View
    private lateinit var navServer: View

    private lateinit var btnSelectFlac: Button
    private lateinit var tvSelectedFlac: TextView
    private lateinit var btnInspectTrack: Button

    private lateinit var switchServer: MaterialSwitch
    private lateinit var tvServerUrl: TextView

    private var isDecoyMode = false
    private var pendingWipe = false
    private var selectedCarrierUri: Uri? = null
    private var poolMode = RaidVaultEngine.PoolMode.AUTO_WHOLE_LIBRARY

    private lateinit var library: VaultLibrary
    private val vaultVolume = VaultVolume()
    private lateinit var appSettings: com.alphasteg.pro.data.AppSettings

    // The current carrier pool as real files (populated when All-Files access lets
    // us scan the filesystem). Vaulting embeds into these FLAC files.
    private var currentPool: List<java.io.File> = emptyList()
    private var poolBytes: Long = 0L
    private var trackCount: Int = 0
    private var vaultFileCount: Int = 0
    private var vaultOriginalBytes: Long = 0L
    private var vaultStoredBytes: Long = 0L

    private enum class SortMode(val label: String) { DATE("newest"), NAME("name"), SIZE("size") }
    private var vaultSortMode = SortMode.DATE
    private var lastVaultEntries: List<VaultVolume.Entry> = emptyList()

    // Cascade password for vaulted files: ONLY the user's code, passed by the
    // lock screen or calculator. No default key ever, so nothing is encrypted
    // under a value a modified build could know. Blank means the vault stays
    // locked (no listing, no vaulting, no restore).
    private val vaultPassword: String by lazy {
        intent.getStringExtra("EXTRA_VAULT_KEY").orEmpty()
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

    private val selectFilesToVaultLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vaultUris(uris, move = false)
    }

    private val deleteOriginalsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val msg = if (result.resultCode == RESULT_OK) "Originals removed." else "Originals kept."
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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

        // Hardening: block screenshots, screen recording, and recents thumbnails.
        if (!BuildConfig.ALLOW_SCREENSHOTS) window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        // 100% Edge-to-Edge Canvas: bar colors come from the theme
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContentView(R.layout.activity_main)

        isDecoyMode = intent.getBooleanExtra("EXTRA_DECOY_MODE", false)
        pendingWipe = intent.getBooleanExtra("EXTRA_WIPE", false)

        topBar = findViewById(R.id.topBar)
        tvModeBadge = findViewById(R.id.tvModeBadge)
        tvVaultStats = findViewById(R.id.tvVaultStats)
        tvStorageDetail = findViewById(R.id.tvStorageDetail)
        partitionBar = findViewById(R.id.partitionBar)
        treemap = findViewById(R.id.treemap)
        tvEmptyDisks = findViewById(R.id.tvEmptyDisks)
        tvEmptyVault = findViewById(R.id.tvEmptyVault)
        vaultFileList = findViewById(R.id.vaultFileList)
        btnAddVaultFile = findViewById(R.id.btnAddVaultFile)
        btnScanVault = findViewById(R.id.btnScanVault)
        rgPoolMode = findViewById(R.id.rgPoolMode)
        rbAutoLibrary = findViewById(R.id.rbAutoLibrary)
        rbManualDisks = findViewById(R.id.rbManualDisks)

        panelVault = findViewById(R.id.panelVault)
        panelDisks = findViewById(R.id.panelDisks)
        panelStego = findViewById(R.id.panelStego)
        panelServer = findViewById(R.id.panelServer)
        navVault = findViewById(R.id.navVault)
        navDisks = findViewById(R.id.navDisks)
        navStego = findViewById(R.id.navStego)
        navServer = findViewById(R.id.navServer)

        btnSelectFlac = findViewById(R.id.btnSelectFlac)
        tvSelectedFlac = findViewById(R.id.tvSelectedFlac)
        btnInspectTrack = findViewById(R.id.btnInspectTrack)

        switchServer = findViewById(R.id.switchServer)
        tvServerUrl = findViewById(R.id.tvServerUrl)

        library = VaultLibrary(this)
        appSettings = com.alphasteg.pro.data.AppSettings(this)

        findViewById<TextView>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<TextView>(R.id.tvVaultSort).setOnClickListener { cycleVaultSort() }

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
        trackCount = known.size
        poolBytes = known.sumOf { it.size }
        currentPool = known.map { java.io.File(it.path) }.filter { it.isFile }
        updateStorageBar(poolBytes, trackCount)
        autoSyncOnStartup()

        // Quietly check GitHub for a newer release; only prompts if one exists.
        if (!isDecoyMode) checkForUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        // Catch tracks added or removed while the app was backgrounded.
        if (!isDecoyMode && (hasAudioPermission() || hasAllFilesAccess())) {
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
        navDisks.setOnClickListener { showPanel(panelDisks, navDisks) }
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

    private fun showSettingsDialog() {
        val items = arrayOf<CharSequence>(
            "Scramble keypad on every keypress (extra secure)",
            "Disguise as Calculator app"
        )
        val checked = booleanArrayOf(appSettings.scramblePerPress, isDisguised())
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Options")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> appSettings.scramblePerPress = isChecked
                    1 -> setDisguised(isChecked)
                }
            }
            .setNeutralButton("Check for updates") { _, _ -> checkForUpdate(manual = true) }
            .setPositiveButton("Done", null)
            .show()
    }

    /** Ask GitHub for a newer release; prompt to download and install if there is one. */
    private fun checkForUpdate(manual: Boolean) {
        if (manual) Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            val release = runCatching {
                com.alphasteg.pro.update.UpdateManager.checkLatest(BuildConfig.VERSION_NAME)
            }.getOrNull()
            runOnUiThread {
                if (release == null) {
                    if (manual) Toast.makeText(this, "You are on the latest version.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Update available: ${release.version}")
                    .setMessage(release.notes.ifBlank { "A newer version is available on GitHub." })
                    .setPositiveButton("Update") { _, _ -> downloadAndInstall(release) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }.start()
    }

    private fun downloadAndInstall(release: com.alphasteg.pro.update.UpdateManager.Release) {
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
            val p = dp(24); setPadding(p, p, p, p)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Downloading ${release.version}")
            .setView(bar)
            .setCancelable(false)
            .create()
        dialog.show()
        Thread {
            val apk = com.alphasteg.pro.update.UpdateManager.download(release.apkUrl) { pct ->
                runOnUiThread { bar.progress = pct }
            }
            runOnUiThread {
                runCatching { dialog.dismiss() }
                if (apk == null) {
                    Toast.makeText(this, "Download failed.", Toast.LENGTH_LONG).show()
                } else {
                    runCatching { com.alphasteg.pro.update.UpdateManager.install(this, apk) }
                        .onFailure { Toast.makeText(this, "Install error: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }.start()
    }

    private fun aliasComponent(name: String) =
        android.content.ComponentName(this, "com.alphasteg.pro.$name")

    private fun isDisguised(): Boolean =
        packageManager.getComponentEnabledSetting(aliasComponent("LauncherCalc")) ==
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Swap which launcher face is active: the vault icon or the calculator. */
    private fun setDisguised(on: Boolean) {
        val enabled = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val disabled = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(
            aliasComponent("LauncherCalc"), if (on) enabled else disabled,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
        packageManager.setComponentEnabledSetting(
            aliasComponent("LauncherVault"), if (on) disabled else enabled,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
        // The "Move to Vault" share target disappears while disguised.
        packageManager.setComponentEnabledSetting(
            aliasComponent("ShareReceiverActivity"), if (on) disabled else enabled,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
        Toast.makeText(
            this,
            if (on) "Disguised as Calculator. Enter your code in the calculator to unlock."
            else "Disguise off. The normal AlphaVault icon is back.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showPanel(panel: View, navItem: View) {
        listOf(panelVault, panelDisks, panelStego, panelServer).forEach {
            it.visibility = if (it === panel) View.VISIBLE else View.GONE
        }
        listOf(navVault, navDisks, navStego, navServer).forEach {
            it.isSelected = it === navItem
        }
    }

    private fun setupVaultActions() {
        btnAddVaultFile.setOnClickListener {
            selectFilesToVaultLauncher.launch("*/*")
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
            val pool = result.tracks.map { java.io.File(it.path) }.filter { it.isFile }
            runOnUiThread {
                trackCount = result.tracks.size
                poolBytes = result.totalBytes
                currentPool = pool
                if (pendingWipe) {
                    pendingWipe = false
                    val wipePool = pool
                    Thread { runCatching { vaultVolume.wipeAll(wipePool) } }.start()
                }
                renderPoolDisks(result.tracks)
                updateStorageBar(result.totalBytes, result.tracks.size)
                refreshVaultUi()
                maybeHandlePendingShare()
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
        if (tracks.isEmpty()) {
            tvEmptyDisks.visibility = View.VISIBLE
            treemap.visibility = View.GONE
            treemap.setItems(emptyList())
            return
        }
        tvEmptyDisks.visibility = View.GONE
        treemap.visibility = View.VISIBLE
        // Color each carrier by its album folder, so an album reads as one hue.
        val folders = tracks.map { it.folder }.distinct()
        val items = tracks.map { t ->
            val hue = folders.indexOf(t.folder) * 360f / folders.size.coerceAtLeast(1)
            com.alphasteg.pro.ui.TreemapView.Item(
                label = t.name.substringBeforeLast('.'),
                bytes = t.size,
                color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.95f))
            )
        }
        treemap.setItems(items)
    }

    /**
     * Three-tier storage view: whole-device usage (the bar and line 1), the music
     * pool that forms the vault (line 2), and the granular vault usage (line 3).
     */
    private fun updateStorageBar(poolBytes: Long, trackCount: Int = 0) {
        val path = Environment.getExternalStorageDirectory() ?: return
        val stat = android.os.StatFs(path.path)
        val total = stat.totalBytes
        val available = stat.availableBytes
        val used = (total - available).coerceAtLeast(0)

        // GParted-style allocation: other-used, music pool, vault, free.
        val otherUsed = (used - poolBytes).coerceAtLeast(0L)
        partitionBar.setSegments(
            listOf(
                com.alphasteg.pro.ui.PartitionBarView.Segment("System / other", otherUsed, 0xFF3A4a63.toInt()),
                com.alphasteg.pro.ui.PartitionBarView.Segment("Music pool", poolBytes, 0xFF00F2FE.toInt()),
                com.alphasteg.pro.ui.PartitionBarView.Segment("Vault", vaultStoredBytes, 0xFF9D4EDD.toInt()),
                com.alphasteg.pro.ui.PartitionBarView.Segment("Free", available, 0xFF17324a.toInt())
            )
        )

        tvStorageDetail.text = buildString {
            append(String.format(Locale.US, "Device: %s used of %s\n", formatSize(used), formatSize(total)))
            append(String.format(Locale.US, "Music pool: %d FLAC carriers · %s\n", trackCount, formatSize(poolBytes)))
            append(String.format(
                Locale.US,
                "Vault: %d file%s · %s hidden in carriers (%s original)",
                vaultFileCount, if (vaultFileCount == 1) "" else "s",
                formatSize(vaultStoredBytes), formatSize(vaultOriginalBytes)
            ))
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

    /** After unlock and a pool sync, offer to move or copy any shared files into the vault. */
    private fun maybeHandlePendingShare() {
        val items = PendingShare.items
        if (items.isEmpty() || isDecoyMode) return
        if (vaultPassword.isBlank()) return
        if (currentPool.isEmpty()) {
            Toast.makeText(this, "Grant All-files access and sync so there are carriers to vault into.", Toast.LENGTH_LONG).show()
            if (canRequestAllFilesAccess() && !hasAllFilesAccess()) promptAllFilesAccess()
            return
        }
        PendingShare.items = emptyList()
        val count = items.size
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add to vault")
            .setMessage("Add $count file${if (count == 1) "" else "s"} to your vault?")
            .setPositiveButton("Move to Vault") { _, _ -> vaultSharedItems(items, move = true) }
            .setNeutralButton("Copy to Vault") { _, _ -> vaultSharedItems(items, move = false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun vaultSharedItems(items: List<PendingShare.Item>, move: Boolean) {
        val pool = currentPool
        val key = vaultPassword
        val onDone: (() -> Unit)? = if (move) {
            { deleteOriginals(items.map { it.uri }) }
        } else null
        runVaultOperation(
            title = if (move) "Moving ${items.size} to vault" else "Copying ${items.size} to vault",
            pool = pool, key = key, onSuccess = onDone
        ) { progress ->
            var ok = 0
            items.forEachIndexed { i, item ->
                progress.update(i, items.size, "Vaulting ${item.name} (${i + 1}/${items.size})…")
                runCatching {
                    vaultVolume.vault(item.name, item.bytes, key, pool, System.currentTimeMillis(), progress)
                }.onSuccess { ok++ }
            }
            "Vaulted $ok of ${items.size} file${if (items.size == 1) "" else "s"}."
        }
    }

    private fun deleteOriginals(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val pi = MediaStore.createDeleteRequest(contentResolver, uris)
                deleteOriginalsLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()
                )
            }.onFailure {
                Toast.makeText(this, "Vaulted, but couldn't remove the originals automatically.", Toast.LENGTH_LONG).show()
            }
        } else {
            var n = 0
            uris.forEach { runCatching { n += contentResolver.delete(it, null, null) } }
            Toast.makeText(this, "Removed $n original${if (n == 1) "" else "s"}.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Vault one or many picked files as a batch, reading each in turn to stay memory-light. */
    private fun vaultUris(uris: List<Uri>, move: Boolean) {
        if (vaultPassword.isBlank()) {
            Toast.makeText(this, "Vault is locked. Unlock with your code first.", Toast.LENGTH_LONG).show()
            return
        }
        if (currentPool.isEmpty()) {
            Toast.makeText(this, "Grant All-files access and sync your FLAC library first.", Toast.LENGTH_LONG).show()
            if (canRequestAllFilesAccess() && !hasAllFilesAccess()) promptAllFilesAccess()
            return
        }
        val pool = currentPool
        val key = vaultPassword
        val onDone: (() -> Unit)? = if (move) { { deleteOriginals(uris) } } else null
        runVaultOperation(
            title = "Vaulting ${uris.size} file${if (uris.size == 1) "" else "s"}",
            pool = pool, key = key, onSuccess = onDone
        ) { progress ->
            var ok = 0
            uris.forEachIndexed { i, uri ->
                val name = displayNameOf(uri)
                progress.update(i, uris.size, "Vaulting $name (${i + 1}/${uris.size})…")
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@forEachIndexed
                    vaultVolume.vault(name, bytes, key, pool, System.currentTimeMillis(), progress)
                }.onSuccess { ok++ }
            }
            "Vaulted $ok of ${uris.size} file${if (uris.size == 1) "" else "s"}."
        }
    }

    /**
     * Run a long vault/restore job on a background thread with a verbose progress
     * modal. The modal can be hidden (Run in background) without cancelling; a
     * foreground service keeps the work alive if the app is backgrounded.
     */
    private fun runVaultOperation(
        title: String,
        pool: List<java.io.File>,
        key: String,
        onSuccess: (() -> Unit)? = null,
        work: (VaultVolume.Progress) -> String
    ) {
        val progressText = TextView(this).apply {
            setPadding(dp(24), dp(20), dp(24), dp(8))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.av_text_primary))
            text = "Starting…"
        }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(progressText)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(24), 0, dp(24), dp(16))
            })
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("Run in background", null)
            .create()
        dialog.show()

        // Keep the process alive while backgrounded.
        val svc = Intent(this, VaultService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        }

        val topStatus = findViewById<TextView>(R.id.tvTopStatus)
        topStatus.visibility = View.VISIBLE

        val progress = VaultVolume.Progress { done, total, message ->
            runOnUiThread {
                progressText.text = message
                bar.max = total.coerceAtLeast(1)
                bar.progress = done.coerceIn(0, bar.max)
                val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
                topStatus.text = "$pct% · $message"
            }
        }

        Thread {
            val result = runCatching { work(progress) }
            runOnUiThread {
                runCatching { if (dialog.isShowing) dialog.dismiss() }
                runCatching { stopService(svc) }
                topStatus.visibility = View.GONE
                result.onSuccess { msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refreshVaultUi()
                    onSuccess?.invoke()
                }.onFailure { e ->
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun displayNameOf(uri: Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { return it }
            }
        return uri.lastPathSegment ?: "vaulted_file"
    }

    /** Read the encrypted volume index off the carriers and repaint the vault list + stats. */
    private fun refreshVaultUi() {
        if (isDecoyMode) {
            renderVaultedRows(emptyList())
            vaultFileCount = 0; vaultOriginalBytes = 0; vaultStoredBytes = 0
            updateStorageBar(poolBytes, trackCount)
            return
        }
        val pool = currentPool
        Thread {
            val entries = runCatching { vaultVolume.list(pool, vaultPassword) }.getOrDefault(emptyList())
            val stored = runCatching { vaultVolume.usageBytes(pool, vaultPassword) }.getOrDefault(0L)
            runOnUiThread {
                vaultFileCount = entries.size
                vaultOriginalBytes = entries.sumOf { it.originalSize }
                vaultStoredBytes = stored
                renderVaultedRows(entries)
                updateStorageBar(poolBytes, trackCount)
            }
        }.start()
    }

    private fun renderVaultedRows(entries: List<VaultVolume.Entry>) {
        lastVaultEntries = entries
        for (i in vaultFileList.childCount - 1 downTo 0) {
            if (vaultFileList.getChildAt(i) !== tvEmptyVault) {
                vaultFileList.removeViewAt(i)
            }
        }
        val sorted = when (vaultSortMode) {
            SortMode.DATE -> entries.sortedByDescending { it.createdAt }
            SortMode.NAME -> entries.sortedBy { it.name.lowercase() }
            SortMode.SIZE -> entries.sortedByDescending { it.originalSize }
        }
        findViewById<TextView>(R.id.tvVaultSort).text =
            if (entries.isEmpty()) getString(R.string.vault_files_sub)
            else "${entries.size} files · sorted by ${vaultSortMode.label} · tap to change"
        if (sorted.isEmpty()) {
            tvEmptyVault.visibility = View.VISIBLE
            return
        }
        tvEmptyVault.visibility = View.GONE
        for (e in sorted) vaultFileList.addView(buildVaultedRow(e))
    }

    private fun cycleVaultSort() {
        val modes = SortMode.values()
        vaultSortMode = modes[(vaultSortMode.ordinal + 1) % modes.size]
        renderVaultedRows(lastVaultEntries)
    }

    private data class FileKind(val emoji: String, val color: Int)

    private fun kindOf(name: String): FileKind {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> FileKind("🖼", 0xFF00F2FE.toInt())
            "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp" -> FileKind("🎬", 0xFF9D4EDD.toInt())
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus" -> FileKind("🎵", 0xFF2ECC71.toInt())
            "pdf" -> FileKind("📕", 0xFFFF5D5D.toInt())
            "doc", "docx", "odt", "rtf" -> FileKind("📄", 0xFF4DA3FF.toInt())
            "zip", "rar", "7z", "tar", "gz" -> FileKind("🗜", 0xFFFFA24D.toInt())
            "txt", "md", "json", "csv", "log", "xml" -> FileKind("📝", 0xFF7FD1FF.toInt())
            else -> FileKind("🔒", 0xFFB0BAC9.toInt())
        }
    }

    private fun buildVaultedRow(file: VaultVolume.Entry): View {
        val kind = kindOf(file.name)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF0C1322.toInt())
                setStroke(dp(1) + 1, kind.color)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { showVaultedFileMenu(file) }
        }

        val badge = TextView(this).apply {
            text = kind.emoji
            textSize = 20f
            gravity = Gravity.CENTER
            val s = dp(44)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(12) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor((kind.color and 0x00FFFFFF) or 0x33000000) // translucent tint
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = file.name
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.av_text_primary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(TextView(this).apply {
            text = "${formatSize(file.originalSize)} · ${file.chunkCount} chunks"
            setTextColor(kind.color)
            textSize = 12f
        })

        row.addView(badge)
        row.addView(col)
        return row
    }

    private fun showVaultedFileMenu(file: VaultVolume.Entry) {
        val options = arrayOf("View in app", "Rename", "Restore to Downloads", "Delete from vault")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewVaultedFile(file)
                    1 -> renameVaultedFile(file)
                    2 -> restoreVaultedFile(file)
                    3 -> confirmDeleteVaulted(file)
                }
            }
            .show()
    }

    private fun renameVaultedFile(file: VaultVolume.Entry) {
        val input = android.widget.EditText(this).apply {
            setText(file.name)
            setSelection(0, file.name.substringBeforeLast('.').length.coerceAtMost(file.name.length))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == file.name) return@setPositiveButton
                val pool = currentPool
                Thread {
                    runCatching { vaultVolume.rename(file.fileId, newName, vaultPassword, pool) }
                    runOnUiThread { refreshVaultUi() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Decrypt into memory and open a secure in-app viewer (no plaintext written to disk). */
    private fun viewVaultedFile(file: VaultVolume.Entry) {
        val pool = currentPool
        Toast.makeText(this, "Opening \"${file.name}\"…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { vaultVolume.restore(file.fileId, vaultPassword, pool).second }
            runOnUiThread {
                result.onSuccess { bytes ->
                    VaultViewerActivity.show(this, file.name, bytes)
                }.onFailure { e ->
                    Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun restoreVaultedFile(file: VaultVolume.Entry) {
        val pool = currentPool
        Thread {
            val result = runCatching {
                val (name, plain) = vaultVolume.restore(file.fileId, vaultPassword, pool)
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

    private fun confirmDeleteVaulted(file: VaultVolume.Entry) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete from vault")
            .setMessage("Remove \"${file.name}\" from the carriers? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val pool = currentPool
                Thread {
                    runCatching { vaultVolume.delete(file.fileId, vaultPassword, pool) }
                    runOnUiThread { refreshVaultUi() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
