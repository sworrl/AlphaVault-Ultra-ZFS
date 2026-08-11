package com.alphasteg.pro

import com.alphasteg.pro.engine.CryptoEngine
import com.alphasteg.pro.engine.LsbStego
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LsbStegoTest {

    /** Pseudo-real carrier: varied upper bits so the per-carrier key is well-defined. */
    private fun carrier(n: Int, seed: Int = 1): ShortArray {
        var s = seed
        return ShortArray(n) { s = s * 1103515245 + 12345; (s ushr 8).toShort() }
    }

    @Test
    fun roundTripsAPayload() {
        val samples = carrier(200_000)
        val payload = ByteArray(500) { (it * 7).toByte() }
        LsbStego.embed(samples, payload, "c0ffee42")
        val out = LsbStego.extract(samples, "c0ffee42")
        assertArrayEquals(payload, out)
    }

    @Test
    fun wrongCodeRecoversNothing() {
        val samples = carrier(200_000)
        LsbStego.embed(samples, ByteArray(300) { it.toByte() }, "rightCode1")
        // Wrong code derives different positions and marker, so it sees noise.
        assertNull(LsbStego.extract(samples, "wrongCode9"))
    }

    @Test
    fun emptyCarrierForACodeReturnsNull() {
        // Nothing embedded: the keyed marker will almost never match by chance.
        assertNull(LsbStego.extract(carrier(50_000), "anyCode123"))
    }

    @Test
    fun onlyLeastSignificantBitsChange() {
        val original = carrier(120_000)
        val samples = original.copyOf()
        LsbStego.embed(samples, ByteArray(400) { (it * 3).toByte() }, "code12345")
        for (i in samples.indices) {
            assertEquals("upper bits of sample $i changed",
                original[i].toInt() and 0xFFFE, samples[i].toInt() and 0xFFFE)
        }
    }

    @Test
    fun survivesTheFullEncryptThenHideRoundTrip() {
        // The real pipeline: cascade-encrypt, hide in LSBs, recover, decrypt.
        val plfrom = "meet at pier 7 at midnight".toByteArray()
        val cipher = CryptoEngine.encryptPayload(plfrom, "s3cr3tCode!")
        val samples = carrier(300_000)
        LsbStego.embed(samples, cipher, "s3cr3tCode!")
        val recovered = LsbStego.extract(samples, "s3cr3tCode!")!!
        val plain = CryptoEngine.decryptPayload(recovered, "s3cr3tCode!")
        assertArrayEquals(plfrom, plain)
    }

    @Test
    fun capacityIsReportedAndEnforced() {
        val samples = carrier(8_000)
        val cap = LsbStego.capacityBytes(samples.size)
        assertTrue(cap in 900..1000) // 8000/8 - 12
        try {
            LsbStego.embed(samples, ByteArray(cap + 1), "code")
            throw AssertionError("should reject an oversized payload")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun differentCarriersKeyIndependently() {
        // Same code, two different carriers: each derives its own key from its audio.
        val a = carrier(150_000, seed = 11)
        val b = carrier(150_000, seed = 22)
        LsbStego.embed(a, "alpha".toByteArray(), "sameCode00")
        LsbStego.embed(b, "bravo".toByteArray(), "sameCode00")
        assertArrayEquals("alpha".toByteArray(), LsbStego.extract(a, "sameCode00"))
        assertArrayEquals("bravo".toByteArray(), LsbStego.extract(b, "sameCode00"))
    }
}
