package com.alphasteg.pro.engine

import java.security.MessageDigest

/**
 * Least-significant-bit audio steganography, ported from bennjordan/AlphaSteg's
 * `lsb` method and hardened so it leaves no fingerprint.
 *
 * Upstream writes `[0xAF,0x55] + length + payload` into the sample LSBs in order,
 * which a scanner detects by that fixed marker. Here there is no fixed marker and
 * no fixed order:
 *
 *  - The embedding key is derived from the code and a hash of the samples' upper
 *    15 bits. Those upper bits never change when the LSB is written, and FLAC is
 *    lossless, so the key is recoverable from the carrier without storing a salt.
 *  - That key seeds a PRNG that chooses which sample LSBs carry bits and in what
 *    order, so without the code the bits, their order, and the length are all
 *    unknown; a signature scan sees only noise.
 *  - A keyed marker (derived from the same key) confirms "data for this code is
 *    present" without being a universal fingerprint.
 *
 * The payload handed in is expected to be already encrypted (see [CryptoEngine]);
 * this layer hides it, it does not add confidentiality on its own.
 */
object LsbStego {

    private const val MARKER_LEN = 8          // keyed presence marker
    private const val LENGTH_LEN = 4          // payload length, big-endian
    private const val HEADER_LEN = MARKER_LEN + LENGTH_LEN

    /** Bytes that can be hidden in [sampleCount] samples (1 bit per sample). */
    fun capacityBytes(sampleCount: Int): Int = (sampleCount / 8) - HEADER_LEN

    /**
     * Hide [payload] in the LSBs of [samples] (modified in place). Throws if the
     * carrier is too short. Only bit 0 of each chosen sample is touched.
     */
    fun embed(samples: ShortArray, payload: ByteArray, code: String) {
        val need = HEADER_LEN + payload.size
        require(need <= capacityBytes(samples.size) + HEADER_LEN) {
            "Carrier holds ${capacityBytes(samples.size)} bytes; need ${payload.size}."
        }
        val key = deriveKey(samples, code)
        val marker = markerFrom(key)
        val header = ByteArray(HEADER_LEN)
        System.arraycopy(marker, 0, header, 0, MARKER_LEN)
        header[MARKER_LEN] = (payload.size ushr 24).toByte()
        header[MARKER_LEN + 1] = (payload.size ushr 16).toByte()
        header[MARKER_LEN + 2] = (payload.size ushr 8).toByte()
        header[MARKER_LEN + 3] = payload.size.toByte()

        val positions = KeyedPositions(key, samples.size)
        writeBits(samples, header, positions)
        writeBits(samples, payload, positions)
    }

    /**
     * Recover a payload hidden by [embed] with the same [code], or null if this
     * carrier holds nothing for this code.
     */
    fun extract(samples: ShortArray, code: String): ByteArray? {
        if (samples.size < HEADER_LEN * 8) return null
        val key = deriveKey(samples, code)
        val positions = KeyedPositions(key, samples.size)

        val header = readBytes(samples, HEADER_LEN, positions)
        val expectedMarker = markerFrom(key)
        for (i in 0 until MARKER_LEN) if (header[i] != expectedMarker[i]) return null

        val len = ((header[MARKER_LEN].toInt() and 0xFF) shl 24) or
            ((header[MARKER_LEN + 1].toInt() and 0xFF) shl 16) or
            ((header[MARKER_LEN + 2].toInt() and 0xFF) shl 8) or
            (header[MARKER_LEN + 3].toInt() and 0xFF)
        if (len < 0 || len > capacityBytes(samples.size)) return null

        return readBytes(samples, len, positions)
    }

    // ---- key / marker ----

    /** Stable per-carrier key: SHA-512(code ‖ hash of the samples' upper 15 bits). */
    private fun deriveKey(samples: ShortArray, code: String): ByteArray {
        val upper = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(2)
        for (s in samples) {
            val hi = s.toInt() and 0xFFFE   // drop bit 0, the LSB we may change
            buf[0] = (hi ushr 8).toByte()
            buf[1] = hi.toByte()
            upper.update(buf)
        }
        val msbHash = upper.digest()
        val k = MessageDigest.getInstance("SHA-512")
        k.update(code.toByteArray(Charsets.UTF_8))
        k.update(msbHash)
        return k.digest()
    }

    private fun markerFrom(key: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-512")
        d.update(key)
        d.update("AVLSB-marker".toByteArray(Charsets.UTF_8))
        return d.digest().copyOf(MARKER_LEN)
    }

    // ---- bit I/O over keyed positions ----

    private fun writeBits(samples: ShortArray, data: ByteArray, positions: KeyedPositions) {
        for (b in data) {
            val v = b.toInt()
            for (j in 7 downTo 0) {
                val bit = (v ushr j) and 1
                val idx = positions.next()
                samples[idx] = ((samples[idx].toInt() and 0xFFFE) or bit).toShort()
            }
        }
    }

    private fun readBytes(samples: ShortArray, count: Int, positions: KeyedPositions): ByteArray {
        val out = ByteArray(count)
        for (i in 0 until count) {
            var v = 0
            for (j in 0 until 8) {
                val idx = positions.next()
                v = (v shl 1) or (samples[idx].toInt() and 1)
            }
            out[i] = v.toByte()
        }
        return out
    }

    /**
     * Deterministic stream of distinct sample indices in [0, n), keyed by the
     * carrier key. Encode and decode walk the identical stream, so header and
     * payload land on the same positions without anything being stored.
     */
    private class KeyedPositions(key: ByteArray, private val n: Int) {
        private val rng = SplitMix64(seedOf(key))
        private val used = BooleanArray(n)

        fun next(): Int {
            while (true) {
                val i = (rng.next() ushr 1).mod(n.toLong()).toInt()
                if (!used[i]) { used[i] = true; return i }
            }
        }

        private companion object {
            fun seedOf(key: ByteArray): Long {
                var s = 0L
                for (i in 0 until 8) s = (s shl 8) or (key[i].toLong() and 0xFF)
                return s
            }
        }
    }

    /** Small deterministic PRNG (SplitMix64) for keyed position selection. */
    private class SplitMix64(private var state: Long) {
        fun next(): Long {
            state += -0x61c8864680b583ebL // golden-ratio increment 0x9E3779B97F4A7C15
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
