package com.alphasteg.pro

import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Can this app read and write a removable volume?
 *
 * The whole "DAC is just dumb storage" model rests on it. The card comes out of
 * the player into a reader on the phone, the phone vaults into the FLACs on it,
 * and the card goes back - so the app needs ordinary read/write access to
 * `/storage/<UUID>` paths. Reading has never been the problem; writing to
 * removable media is what Android has narrowed version by version, and if it
 * fails there is no workflow.
 *
 * This reports rather than asserts, because the answer depends on the device, the
 * OS version, and whether All-files access has been granted. Run it on the target
 * hardware and read the log:
 *
 *   adb shell appops set com.alphasteg.pro MANAGE_EXTERNAL_STORAGE allow
 *   ./gradlew connectedProdDebugAndroidTest
 *   adb logcat -d -s AlphaVaultStorage
 */
@RunWith(AndroidJUnit4::class)
class RemovableWriteTest {

    private val tag = "AlphaVaultStorage"

    @Test
    fun reportsRemovableVolumeAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val hasAllFiles = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        Log.i(tag, "=== storage access ===")
        Log.i(tag, "sdk=${Build.VERSION.SDK_INT} allFilesAccess=$hasAllFiles")

        val volumes = ArrayList<Pair<String, File>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
            for (v in sm?.storageVolumes.orEmpty()) {
                val dir = v.directory ?: continue
                volumes.add((if (v.isRemovable) "removable:" else "primary:") + dir.name to dir)
                Log.i(
                    tag,
                    "volume ${dir.absolutePath} removable=${v.isRemovable} " +
                        "state=${v.state} readable=${dir.canRead()} writable=${dir.canWrite()}"
                )
            }
        }
        // Anything mounted that the volume API did not describe.
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "self" && volumes.none { it.second == f }) {
                Log.i(tag, "bare /storage entry ${f.absolutePath} readable=${f.canRead()} writable=${f.canWrite()}")
                volumes.add("bare:${f.name}" to f)
            }
        }

        for ((label, root) in volumes) {
            probe(label, root)
        }

        assertTrue("at least one volume must be visible", volumes.isNotEmpty())
    }

    /** Try the things a vault actually does: list, create, write, read back, replace, delete. */
    private fun probe(label: String, root: File) {
        Log.i(tag, "--- $label ${root.absolutePath} ---")

        val entries = runCatching { root.listFiles()?.size }.getOrNull()
        Log.i(tag, "  list: ${entries ?: "denied"}")

        val flacs = runCatching {
            root.listFiles()?.count { it.isFile && it.extension.equals("flac", true) }
        }.getOrNull()
        if (flacs != null) Log.i(tag, "  flac files at top level: $flacs")

        // java.io rather than Kotlin's File extensions: the app is R8-minified even
        // in debug, and stdlib helpers the app itself never calls get shrunk out
        // from under the test APK.
        val probe = File(root, ".alphavault-write-probe")
        val created = runCatching {
            java.io.FileOutputStream(probe).use { it.write(ByteArray(1024) { i -> i.toByte() }) }
            probe.exists()
        }.getOrElse {
            Log.i(tag, "  create: DENIED (${it.javaClass.simpleName}: ${it.message})")
            false
        }
        if (!created) {
            Log.i(tag, "  => NOT writable; vaulting into this volume is impossible")
            return
        }
        Log.i(tag, "  create: ok")

        // A carrier embed rewrites the file in place via a temp file and a rename,
        // so renaming within the volume is the operation that must work.
        val readBack = runCatching {
            java.io.FileInputStream(probe).use { input ->
                var total = 0
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                }
                total
            }
        }.getOrNull()
        Log.i(tag, "  read back: ${readBack ?: "failed"} bytes")

        val renamed = File(root, ".alphavault-write-probe-2")
        val renameOk = runCatching { probe.renameTo(renamed) }.getOrDefault(false)
        Log.i(tag, "  rename within volume: ${if (renameOk) "ok" else "DENIED"}")

        val target = if (renameOk) renamed else probe
        val deleted = runCatching { target.delete() }.getOrDefault(false)
        Log.i(tag, "  delete: ${if (deleted) "ok" else "DENIED"}")

        Log.i(
            tag,
            if (renameOk) "  => writable, and rename works: carriers can be rewritten here"
            else "  => writable but rename failed: FlacCarrierEngine's temp-file swap would fall back to a full copy"
        )
    }
}
