package com.alphasteg.pro

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alphasteg.pro.engine.CryptoEngine
import com.alphasteg.pro.engine.LsbStego
import com.alphasteg.pro.engine.RaidVaultEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device cost of the vault engines, aimed at the weakest hardware we support.
 *
 * The HiBy M500 has ~3.7 GB of RAM and a modest CPU, and every engine here works
 * on whole files held in memory, so the interesting question is not "is it fast"
 * but "at what file size does this device fall over". Each case prints its timing
 * and the heap headroom it had, so a run on the DAP can be diffed against a run
 * on the phone.
 *
 * Run with:
 *   ./gradlew connectedProdDebugAndroidTest
 * then read the numbers back with:
 *   adb logcat -d -s AlphaVaultPerf
 */
@RunWith(AndroidJUnit4::class)
class EnginePerfTest {

    private val tag = "AlphaVaultPerf"

    /** Deterministic pseudo-audio: varied upper bits, like a real PCM carrier. */
    private fun carrier(n: Int, seed: Int = 1): ShortArray {
        var s = seed
        return ShortArray(n) { s = s * 1103515245 + 12345; (s ushr 8).toShort() }
    }

    private fun payload(n: Int) = ByteArray(n) { (it * 31).toByte() }

    private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)

    /** Time a block, logging elapsed ms and the heap state around it. */
    private fun <T> timed(label: String, block: () -> T): T {
        val rt = Runtime.getRuntime()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val after = rt.totalMemory() - rt.freeMemory()
        Log.i(tag, "%-38s %8.1f ms   heap %s -> %s of %s max"
            .format(label, ms, mb(before), mb(after), mb(rt.maxMemory())))
        return result
    }

    @Test
    fun reportsDeviceHeadroom() {
        val rt = Runtime.getRuntime()
        Log.i(tag, "=== device ===")
        Log.i(tag, "model=${android.os.Build.MODEL} manufacturer=${android.os.Build.MANUFACTURER} sdk=${android.os.Build.VERSION.SDK_INT}")
        // java.util.Arrays rather than joinToString: the app is R8-minified even in
        // debug, and the stdlib overload gets shrunk out from under the test APK.
        Log.i(tag, "abi=${java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS)}")
        Log.i(tag, "jvm max heap=${mb(rt.maxMemory())}  (this is the ceiling a whole-file ByteArray must fit under)")
        assertTrue("a usable heap is required", rt.maxMemory() > 16L * 1024 * 1024)
    }

    /**
     * AES-GCM over whole files. This is the cheapest stage but it allocates a
     * second full-size buffer, so peak is roughly 2x the file.
     */
    /**
     * The cascade, split into the part that costs and the part that does not.
     *
     * The 500k-iteration PBKDF2 stretch runs once per (password, salt) and is then
     * cached, so the first call in a session pays for it and the rest are pure
     * cipher throughput. This is the difference between a browsable vault and an
     * unusable one on this hardware, so both numbers are reported.
     */
    @Test
    fun cryptoScales() {
        Log.i(tag, "=== CryptoEngine (cascade: PBKDF2 once, then HKDF per payload) ===")
        CryptoEngine.clearKeyCache()

        val warmUp = payload(1024)
        timed("COLD first call (pays the PBKDF2 stretch)") {
            CryptoEngine.encryptPayload(warmUp, "benchPassword1")
        }

        for (sizeMb in listOf(1, 4, 16)) {
            val data = payload(sizeMb * 1024 * 1024)
            val enc = timed("warm encrypt ${sizeMb}MB") { CryptoEngine.encryptPayload(data, "benchPassword1") }
            val dec = timed("warm decrypt ${sizeMb}MB") { CryptoEngine.decryptPayload(enc, "benchPassword1") }
            assertArrayEquals(data, dec)
        }

        // What a browse used to cost: loadIndex decrypts once per candidate carrier.
        val blob = CryptoEngine.encryptPayload(payload(64 * 1024), "benchPassword1")
        timed("index-sized decrypt x4 (a vault browse)") {
            repeat(4) { CryptoEngine.decryptPayload(blob, "benchPassword1") }
        }

        CryptoEngine.clearKeyCache()
        timed("same decrypt after clearKeyCache (re-stretch)") {
            CryptoEngine.decryptPayload(blob, "benchPassword1")
        }
    }

    /**
     * The RAID-Z2 stage. Reed-Solomon Q parity is the CPU-heavy half, and the
     * result holds the data again as chunks plus hot-spare mirrors, so this is
     * the most memory-hungry step in the pipeline.
     */
    @Test
    fun raidScales() {
        Log.i(tag, "=== RaidVaultEngine (RAID-Z2 dual parity + hot spares) ===")
        for (sizeMb in listOf(1, 4, 16)) {
            val data = payload(sizeMb * 1024 * 1024)
            val encoded = timed("encodeRaidZ2 ${sizeMb}MB") {
                RaidVaultEngine.encodeRaidZ2WithHotSpares(data)
            }
            Log.i(tag, "   -> ${encoded.chunks.size} chunks, chunkSize=${mb(encoded.chunkSize.toLong())}")

            // Recover with the two most expensive losses: two data chunks gone,
            // forcing the full P+Q solve rather than a cheap XOR.
            val surviving = encoded.chunks
                .filter { it.chunkIndex != 0 && it.chunkIndex != 1 }
                .associate { it.chunkIndex to it.data }
            val back = timed("reconstruct (2 data chunks lost)") {
                RaidVaultEngine.reconstructRaidZ2(surviving, encoded.totalLength, encoded.chunkSize)
            }
            assertArrayEquals(data, back)
        }
    }

    /**
     * LSB embedding across carrier lengths that match real tracks. A 5-minute
     * 44.1 kHz stereo FLAC is ~26.5M samples, and embedLsb returns a fresh
     * ShortArray, so peak is about 4 bytes per sample plus the payload.
     */
    @Test
    fun lsbScalesToRealTrackLengths() {
        Log.i(tag, "=== LsbStegoEngine (carrier sizes as real tracks) ===")
        // (label, sample count) — stereo interleaved at 44.1 kHz.
        val tracks = listOf(
            "30s track" to 44_100 * 2 * 30,
            "3min track" to 44_100 * 2 * 180,
            "5min track" to 44_100 * 2 * 300
        )
        for ((label, samples) in tracks) {
            val pcm = carrier(samples)
            val hidden = payload(64 * 1024)
            Log.i(tag, "$label: ${samples} samples (${mb(samples.toLong() * 2)} as PCM)")

            // LsbStego is what LsbCarrierEngine actually calls per chunk: keyed
            // placement over an already-encrypted payload, no KDF involved.
            timed("  LsbStego.embed $label") { LsbStego.embed(pcm, hidden, "c0ffee42") }
            val out = timed("  LsbStego.extract $label") { LsbStego.extract(pcm, "c0ffee42") }
            assertArrayEquals(hidden, out)
        }
    }

    /**
     * How big a file the decrypt half of a restore can actually handle.
     *
     * The framed format decrypts a frame at a time into the caller's sink, so the
     * ciphertext plus one frame is the working set rather than several full-size
     * copies. This walks the size up until it fails and reports the last size that
     * worked, which is the number that matters for a small-heap device.
     */
    @Test
    fun findsStreamingDecryptCeiling() {
        Log.i(tag, "=== streaming decrypt ceiling (framed cascade -> sink) ===")
        CryptoEngine.clearKeyCache()
        var lastOk = 0
        for (sizeMb in listOf(8, 16, 32, 64, 96, 128)) {
            val expected = sizeMb.toLong() * 1024 * 1024
            val ok = try {
                // Build the ciphertext, then drop the plaintext before decrypting.
                // Holding both would measure the test harness, not the restore path:
                // a real restore only ever has the ciphertext in hand.
                var enc: ByteArray? = run {
                    val plain = payload(sizeMb * 1024 * 1024)
                    CryptoEngine.encryptPayload(plain, "benchPassword1")
                }
                System.gc()

                // A counting sink: models an export to disk or a socket, where the
                // plaintext is never accumulated in memory.
                val sink = object : java.io.OutputStream() {
                    var total = 0L
                    override fun write(b: Int) { total++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { total += len }
                }
                val ms = System.nanoTime()
                CryptoEngine.decryptTo(enc!!, "benchPassword1", sink)
                val took = (System.nanoTime() - ms) / 1_000_000.0
                Log.i(tag, "  ${sizeMb}MB streamed out in %.0f ms (%d bytes)".format(took, sink.total))
                enc = null
                sink.total == expected
            } catch (e: OutOfMemoryError) {
                false
            }
            Log.i(tag, "  ${sizeMb}MB: ${if (ok) "ok" else "OOM"}")
            if (!ok) break
            lastOk = sizeMb
            System.gc()
        }
        Log.i(tag, "largest file decrypted to a sink: ${lastOk}MB")
    }

    /**
     * The restore path in its real shape: RAID chunks in hand, decrypting straight
     * out of them into a sink without ever assembling the blob.
     *
     * This is what a restore-to-disk actually costs. Rebuilding the payload first
     * would hold the chunks and a full-size copy at the same time, so the number
     * here should beat [findsStreamingDecryptCeiling], which starts from a
     * contiguous ciphertext.
     */
    @Test
    fun findsChunkedRestoreCeiling() {
        Log.i(tag, "=== restore from RAID chunks -> sink (no blob assembled) ===")
        CryptoEngine.clearKeyCache()
        var lastOk = 0
        for (sizeMb in listOf(16, 32, 64, 96, 128)) {
            val expected = sizeMb.toLong() * 1024 * 1024
            val ok = try {
                // Build chunks the way a vault does, then drop everything else:
                // a restore only ever holds the gathered chunks.
                var raid: com.alphasteg.pro.engine.RaidVaultEngine.RaidZ2Result? = run {
                    val enc = CryptoEngine.encryptPayload(payload(sizeMb * 1024 * 1024), "benchPassword1")
                    RaidVaultEngine.encodeRaidZ2WithHotSpares(enc, 4, false)
                }
                System.gc()

                val map = raid!!.chunks.associate { it.chunkIndex to it.data }
                val source = RaidVaultEngine.sourceIfIntact(
                    map, raid!!.totalLength, raid!!.chunkSize, 4
                ) ?: error("chunks were all present, so this must be readable directly")

                val sink = object : java.io.OutputStream() {
                    var total = 0L
                    override fun write(b: Int) { total++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { total += len }
                }
                val t0 = System.nanoTime()
                CryptoEngine.decryptTo(source, "benchPassword1", sink)
                val took = (System.nanoTime() - t0) / 1_000_000.0
                Log.i(tag, "  ${sizeMb}MB restored in %.0f ms (%d bytes)".format(took, sink.total))
                raid = null
                sink.total == expected
            } catch (e: OutOfMemoryError) {
                false
            }
            Log.i(tag, "  ${sizeMb}MB: ${if (ok) "ok" else "OOM"}")
            if (!ok) break
            lastOk = sizeMb
            System.gc()
        }
        Log.i(tag, "largest file restored from chunks: ${lastOk}MB")
    }

    /**
     * The old constraint, kept for comparison: two full-size buffers alive at once,
     * which is what the unframed format forced on every restore.
     */
    @Test
    fun findsWholeFileCeiling() {
        Log.i(tag, "=== whole-file allocation ceiling (restore() returns one ByteArray) ===")
        val rt = Runtime.getRuntime()
        Log.i(tag, "jvm max heap = ${mb(rt.maxMemory())}")
        var lastOk = 0
        for (sizeMb in listOf(16, 32, 64, 128, 256, 512)) {
            val ok = try {
                // Two live buffers: the ciphertext a restore reads and the
                // plaintext it hands back. That pairing is the real constraint.
                val a = ByteArray(sizeMb * 1024 * 1024)
                val b = ByteArray(sizeMb * 1024 * 1024)
                a[0] = 1; b[0] = 1
                true
            } catch (e: OutOfMemoryError) {
                false
            }
            Log.i(tag, "  2 x ${sizeMb}MB buffers: ${if (ok) "ok" else "OOM"}")
            if (!ok) break
            lastOk = sizeMb
            System.gc()
        }
        Log.i(tag, "largest paired allocation that succeeded: ${lastOk}MB " +
            "(a vaulted file above roughly this size cannot be restored in one piece on this device)")
    }
}
