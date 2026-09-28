package com.alphasteg.pro.security

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.alphasteg.pro.DuressWipeService
import com.alphasteg.pro.data.VaultLibrary
import com.alphasteg.pro.data.VaultVolume
import com.alphasteg.pro.engine.FlacCarrierEngine
import com.alphasteg.pro.engine.LsbCarrierEngine
import java.io.File

/**
 * What the duress code actually destroys.
 *
 * The duress path knows only the duress code, never the master, so it cannot
 * decrypt or even locate a keyed payload. It erases by kind instead:
 *
 *  1. The StrongBox key, so no hardware keyslot can be opened again.
 *  2. Every AlphaVault metadata block (AVLT) in every FLAC it can reach: the
 *     library, plus a walk of shared storage when All-Files access allows it.
 *     Only files that carry a block are rewritten.
 *  3. If hidden-audio data was ever written here, the LSB plane of every library
 *     track, index replicas first so the vault stops opening as early as possible.
 *
 * The wipe is persisted as pending before it starts and cleared only when it
 * finishes, so a kill, crash or reboot resumes it on the next launch instead of
 * leaving a half-wiped vault. Credentials are erased by the caller, synchronously,
 * before any of this runs.
 */
object DuressWipe {

    /** Mark the wipe pending and start it. Call right after the credentials are erased. */
    fun begin(context: Context) {
        prefs(context).edit().putBoolean(KEY_PENDING, true).commit()
        StrongBoxKek(context).destroy()
        start(context)
    }

    /** Restart an interrupted wipe. Cheap no-op when none is pending. */
    fun resumeIfPending(context: Context) {
        if (isPending(context)) start(context)
    }

    fun isPending(context: Context): Boolean = prefs(context).getBoolean(KEY_PENDING, false)

    /** Record that hidden-audio data exists, so a later wipe knows to scrub the audio. */
    fun markHiddenData(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_HIDDEN, false)) p.edit().putBoolean(KEY_HIDDEN, true).apply()
    }

    private fun start(context: Context) {
        val svc = Intent(context, DuressWipeService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc)
            else context.startService(svc)
        }
    }

    /** The wipe itself. Blocking; the service runs it off the main thread. */
    fun run(context: Context, progress: VaultVolume.Progress) {
        val p = prefs(context)
        val library = VaultLibrary(context).load().values.map { File(it.path) }.filter { it.isFile }
        val volume = VaultVolume()
        // Index replicas first: once they are gone the vault no longer opens.
        val ordered = (volume.indexCarriers(library) + library).distinct()
        val everything = (ordered + reachableFlacs(context)).distinct()

        everything.forEachIndexed { i, f ->
            progress.update(i, everything.size, f.name)
            runCatching { FlacCarrierEngine.removeMatchingInFile(f) { true } }
        }

        if (p.getBoolean(KEY_HIDDEN, false)) {
            val done = p.getStringSet(KEY_SCRUBBED, emptySet())!!.toMutableSet()
            ordered.forEachIndexed { i, f ->
                if (f.absolutePath in done) return@forEachIndexed
                progress.update(i, ordered.size, f.name)
                runCatching { LsbCarrierEngine.scrub(f) }
                done.add(f.absolutePath)
                p.edit().putStringSet(KEY_SCRUBBED, HashSet(done)).commit()
            }
        }

        p.edit().clear().commit()
    }

    /** FLACs on shared and removable storage, when All-Files access lets us see them. */
    private fun reachableFlacs(context: Context): List<File> {
        val canWalk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true
        if (!canWalk) return emptyList()
        val roots = mutableListOf(Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(StorageManager::class.java)?.storageVolumes
                ?.mapNotNullTo(roots) { it.directory }
        }
        return roots.distinct().flatMap { root ->
            root.walkTopDown()
                .onEnter { it.name != "Android" || it.parentFile != root }
                .filter { it.isFile && it.name.endsWith(".flac", ignoreCase = true) }
                .toList()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("alphavault_wipe", Context.MODE_PRIVATE)

    private const val KEY_PENDING = "pending"
    private const val KEY_HIDDEN = "hidden_written"
    private const val KEY_SCRUBBED = "scrubbed"
}
