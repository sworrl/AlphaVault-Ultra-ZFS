package com.alphasteg.pro.engine

import java.io.File

/**
 * A way to put encrypted payloads into (and take them out of) a FLAC carrier.
 * Two implementations back the two hiding methods the user can pick:
 *
 *  - [MetadataCarrierEngine] : payloads in FLAC APPLICATION metadata blocks. Fast,
 *    audio byte-identical, many payloads per carrier, but the blocks are visible.
 *  - [LsbCarrierEngine]      : one payload hidden in the audio's sample LSBs, keyed
 *    to the code. Presence concealed, but a carrier holds a single payload and its
 *    LSB space belongs to one code.
 *
 * The vault ([VaultVolume]) reads with metadata first (cheap) and falls back to
 * LSB (a full decode), so a library made with either method still opens; it writes
 * with whichever method the user selected.
 */
interface CarrierEngine {
    fun isCarrier(file: File): Boolean
    fun extractAll(file: File): List<ByteArray>
    fun embed(file: File, payload: ByteArray)
    fun removeMatching(file: File, shouldRemove: (ByteArray) -> Boolean): Boolean
}

/** FLAC APPLICATION-block carrier (the fast, visible method). Delegates to [FlacCarrierEngine]. */
object MetadataCarrierEngine : CarrierEngine {
    override fun isCarrier(file: File) = FlacCarrierEngine.isFlacFile(file)
    override fun extractAll(file: File) =
        runCatching { FlacCarrierEngine.extractAllFromFile(file) }.getOrDefault(emptyList())
    override fun embed(file: File, payload: ByteArray) = FlacCarrierEngine.embedIntoFile(file, payload)
    override fun removeMatching(file: File, shouldRemove: (ByteArray) -> Boolean) =
        FlacCarrierEngine.removeMatchingInFile(file, shouldRemove)
}

/**
 * Audio-LSB carrier (the hidden method). Hides one payload in the sample LSBs via
 * [FlacTranscoder] (FLAC<->PCM) and [LsbStego] (keyed embed). A carrier holds a
 * single payload keyed to [code], so the vault assigns one chunk or one index
 * replica per LSB carrier.
 */
class LsbCarrierEngine(private val code: String) : CarrierEngine {

    override fun isCarrier(file: File) = FlacCarrierEngine.isFlacFile(file)

    override fun extractAll(file: File): List<ByteArray> {
        val pcm = runCatching { FlacTranscoder.decode(file) }.getOrNull() ?: return emptyList()
        return LsbStego.extract(pcm.samples, code)?.let { listOf(it) } ?: emptyList()
    }

    override fun embed(file: File, payload: ByteArray) {
        val pcm = FlacTranscoder.decode(file)
        LsbStego.embed(pcm.samples, payload, code) // throws if the carrier is too short
        writeBack(file, pcm)
    }

    override fun removeMatching(file: File, shouldRemove: (ByteArray) -> Boolean): Boolean {
        val pcm = runCatching { FlacTranscoder.decode(file) }.getOrNull() ?: return false
        val payload = LsbStego.extract(pcm.samples, code) ?: return false
        if (!shouldRemove(payload)) return false
        scrubLsbs(pcm.samples) // erase: randomize every LSB so nothing keyed remains
        writeBack(file, pcm)
        return true
    }

    private fun writeBack(file: File, pcm: FlacTranscoder.Pcm) {
        val tmp = File(file.parentFile, file.name + ".avtmp")
        tmp.outputStream().use { FlacTranscoder.encode(pcm, it) }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun scrubLsbs(samples: ShortArray) {
        val rnd = java.security.SecureRandom()
        val bits = ByteArray((samples.size + 7) / 8).also { rnd.nextBytes(it) }
        for (i in samples.indices) {
            val bit = (bits[i / 8].toInt() ushr (i % 8)) and 1
            samples[i] = ((samples[i].toInt() and 0xFFFE) or bit).toShort()
        }
    }
}
