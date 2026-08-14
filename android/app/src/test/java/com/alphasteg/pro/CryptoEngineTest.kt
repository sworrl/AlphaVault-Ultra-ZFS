package com.alphasteg.pro

import com.alphasteg.pro.engine.CryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

class CryptoEngineTest {

    private val pw = "benchPassword1"

    private fun data(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    @Before
    fun freshCache() = CryptoEngine.clearKeyCache()

    // ---- round trips ----

    @Test
    fun roundTripsASmallPayload() {
        val plain = data(1000)
        assertArrayEquals(plain, CryptoEngine.decryptPayload(CryptoEngine.encryptPayload(plain, pw), pw))
    }

    @Test
    fun roundTripsAnEmptyPayload() {
        val enc = CryptoEngine.encryptPayload(ByteArray(0), pw)
        assertTrue(CryptoEngine.isEncryptedPayload(enc))
        assertArrayEquals(ByteArray(0), CryptoEngine.decryptPayload(enc, pw))
    }

    @Test
    fun roundTripsAcrossAFrameBoundary() {
        // Exercises the multi-frame path either side of the exact boundary.
        for (size in listOf(
            CryptoEngine.FRAME_SIZE - 1,
            CryptoEngine.FRAME_SIZE,
            CryptoEngine.FRAME_SIZE + 1,
            CryptoEngine.FRAME_SIZE * 2 + 12345
        )) {
            val plain = data(size)
            val back = CryptoEngine.decryptPayload(CryptoEngine.encryptPayload(plain, pw), pw)
            assertArrayEquals("size=$size", plain, back)
        }
    }

    @Test
    fun blankPasswordPassesDataThrough() {
        val plain = data(50)
        assertArrayEquals(plain, CryptoEngine.encryptPayload(plain, null))
        assertArrayEquals(plain, CryptoEngine.encryptPayload(plain, ""))
    }

    @Test
    fun unencryptedInputIsReturnedUnchanged() {
        val notOurs = "just some bytes".toByteArray()
        assertArrayEquals(notOurs, CryptoEngine.decryptPayload(notOurs, pw))
    }

    // ---- streaming ----

    @Test
    fun streamingMatchesInMemory() {
        val plain = data(CryptoEngine.FRAME_SIZE * 2 + 999)
        val enc = CryptoEngine.encryptPayload(plain, pw)

        val streamed = ByteArrayOutputStream()
        CryptoEngine.decryptTo(enc, pw, streamed)

        assertArrayEquals(plain, streamed.toByteArray())
        assertArrayEquals(CryptoEngine.decryptPayload(enc, pw), streamed.toByteArray())
    }

    @Test
    fun declaredLengthMatchesThePayload() {
        val plain = data(12_345)
        assertEquals(12_345L, CryptoEngine.declaredLength(CryptoEngine.encryptPayload(plain, pw)))
    }

    @Test
    fun nothingIsWrittenWhenAuthenticationFails() {
        val enc = CryptoEngine.encryptPayload(data(4000), pw)
        enc[enc.size / 2] = (enc[enc.size / 2].toInt() xor 0xFF).toByte()

        val sink = ByteArrayOutputStream()
        val failed = runCatching { CryptoEngine.decryptTo(enc, pw, sink) }.isFailure

        assertTrue("tampering must be rejected", failed)
        assertEquals("no plaintext may leak before the HMAC check", 0, sink.size())
    }

    // ---- authentication ----

    @Test
    fun wrongPasswordIsRejected() {
        val enc = CryptoEngine.encryptPayload(data(500), pw)
        assertTrue(runCatching { CryptoEngine.decryptPayload(enc, "notThePassword") }.isFailure)
    }

    @Test
    fun aMissingPasswordIsRejected() {
        val enc = CryptoEngine.encryptPayload(data(500), pw)
        assertTrue(runCatching { CryptoEngine.decryptPayload(enc, null) }.isFailure)
        assertTrue(runCatching { CryptoEngine.decryptPayload(enc, "") }.isFailure)
    }

    @Test
    fun aFlippedBitAnywhereIsRejected() {
        val plain = data(3000)
        // Header, body and trailing MAC each get probed.
        for (at in listOf(10, 45, 60, 200, 1500)) {
            val enc = CryptoEngine.encryptPayload(plain, pw)
            enc[at] = (enc[at].toInt() xor 0x01).toByte()
            assertTrue("byte $at must be authenticated", runCatching {
                CryptoEngine.decryptPayload(enc, pw)
            }.isFailure)
        }
    }

    @Test
    fun truncationIsRejected() {
        val enc = CryptoEngine.encryptPayload(data(CryptoEngine.FRAME_SIZE + 500), pw)
        val cut = enc.copyOfRange(0, enc.size - 200)
        assertTrue(runCatching { CryptoEngine.decryptPayload(cut, pw) }.isFailure)
    }

    @Test
    fun reorderingFramesIsRejected() {
        // Two frames of identical size, so a naive swap would otherwise line up.
        val plain = data(CryptoEngine.FRAME_SIZE * 2)
        val enc = CryptoEngine.encryptPayload(plain, pw)

        // Locate the two frame bodies and swap them wholesale.
        val headerSize = 8 + 32 + 4 + 8
        val frameHeader = 12 + 12 + 4
        val cipherLen = ((enc[headerSize + 24].toInt() and 0xFF) shl 24) or
            ((enc[headerSize + 25].toInt() and 0xFF) shl 16) or
            ((enc[headerSize + 26].toInt() and 0xFF) shl 8) or
            (enc[headerSize + 27].toInt() and 0xFF)
        val frameTotal = frameHeader + cipherLen
        val first = enc.copyOfRange(headerSize, headerSize + frameTotal)
        val second = enc.copyOfRange(headerSize + frameTotal, headerSize + frameTotal * 2)

        val swapped = enc.copyOf()
        System.arraycopy(second, 0, swapped, headerSize, frameTotal)
        System.arraycopy(first, 0, swapped, headerSize + frameTotal, frameTotal)

        assertTrue(
            "frames must be bound to their position",
            runCatching { CryptoEngine.decryptPayload(swapped, pw) }.isFailure
        )
    }

    // ---- key schedule ----

    @Test
    fun eachEncryptionProducesDistinctCiphertext() {
        val plain = data(2000)
        val a = CryptoEngine.encryptPayload(plain, pw)
        val b = CryptoEngine.encryptPayload(plain, pw)
        // Same session salt and master key, but fresh per-frame nonces.
        assertFalse(a.contentEquals(b))
        assertArrayEquals(plain, CryptoEngine.decryptPayload(a, pw))
        assertArrayEquals(plain, CryptoEngine.decryptPayload(b, pw))
    }

    @Test
    fun clearingTheCacheDoesNotBreakDecryption() {
        val plain = data(5000)
        val enc = CryptoEngine.encryptPayload(plain, pw)
        CryptoEngine.clearKeyCache()
        // The salt travels in the envelope, so the master key is re-derivable.
        assertArrayEquals(plain, CryptoEngine.decryptPayload(enc, pw))
    }

    @Test
    fun ciphertextDoesNotContainThePlaintext() {
        val marker = "TOP-SECRET-MARKER-STRING".toByteArray()
        val plain = ByteArray(4096).also { System.arraycopy(marker, 0, it, 100, marker.size) }
        val enc = CryptoEngine.encryptPayload(plain, pw)
        assertFalse(String(enc, Charsets.ISO_8859_1).contains("TOP-SECRET-MARKER-STRING"))
    }

    @Test
    fun envelopeIsRecognisableWithoutTheKey() {
        val enc = CryptoEngine.encryptPayload(data(100), pw)
        assertTrue(CryptoEngine.isEncryptedPayload(enc))
        assertFalse(CryptoEngine.isEncryptedPayload("nope".toByteArray()))
        assertFalse(CryptoEngine.isEncryptedPayload(ByteArray(0)))
    }

    @Test
    fun differentPasswordsGiveDifferentPlaintextSpace() {
        val plain = data(300)
        val a = CryptoEngine.encryptPayload(plain, "passwordOne1")
        assertNotEquals(
            "a second password must not open the first envelope",
            true,
            runCatching { CryptoEngine.decryptPayload(a, "passwordTwo2") }.isSuccess
        )
    }
}
