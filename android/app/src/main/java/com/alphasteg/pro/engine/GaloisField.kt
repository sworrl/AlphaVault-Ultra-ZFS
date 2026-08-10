package com.alphasteg.pro.engine

/**
 * GF(2^8) arithmetic for RAID-6 / RAID-Z2, using the same field as Linux
 * md-raid6 and ZFS: primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11d) and
 * generator g = 2. This is the real Reed-Solomon field those systems use, not an
 * approximation.
 */
object GaloisField {

    private const val POLY = 0x11d
    private const val GENERATOR = 2

    // exp[i] = g^i (period 255, duplicated to 512 so a+b indexing never wraps);
    // log[x] = discrete log of x base g.
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor POLY
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
        log[0] = 0 // unused; log(0) is undefined
    }

    fun mul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return exp[log[a and 0xFF] + log[b and 0xFF]]
    }

    /** Multiplicative inverse of a (a != 0). */
    fun inv(a: Int): Int = exp[255 - log[a and 0xFF]]

    /** g^power, i.e. the RAID-6 coefficient for data disk `power`. */
    fun gExp(power: Int): Int = exp[power % 255]

    /** Multiply every byte of [data] by scalar [s] in GF(2^8), into [out]. */
    fun mulInto(s: Int, data: ByteArray, out: ByteArray) {
        if (s == 0) { out.fill(0); return }
        val logS = log[s and 0xFF]
        for (i in data.indices) {
            val d = data[i].toInt() and 0xFF
            out[i] = if (d == 0) 0 else exp[logS + log[d]].toByte()
        }
    }
}
