package com.alphasteg.pro

import com.alphasteg.pro.engine.CryptoEngine
import com.alphasteg.pro.engine.RaidVaultEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the vault pipeline that runs on the JVM without Android:
 *   plaintext -> cascade encrypt -> RAID-Z2 chunk -> reconstruct -> decrypt.
 * These engines use only javax.crypto and java.security, so they run under a
 * plain unit test.
 */
class VaultRoundTripTest {

    private val password = "correct horse battery staple"

    private fun roundTrip(data: ByteArray) {
        val encrypted = CryptoEngine.encryptPayload(data, password)
        assertFalse("ciphertext must differ from plaintext", encrypted.contentEquals(data))

        val raid = RaidVaultEngine.encodeRaidZ2WithHotSpares(encrypted, numDataChunks = 4, enableHotSpares = true)
        // 4 data + P + Q = 6 primary, mirrored to 6 hot spares = 12.
        assertTrue("expected 12 chunks, got ${raid.chunks.size}", raid.chunks.size == 12)

        val available = raid.chunks.associate { it.chunkIndex to it.data }
        val reconstructed = RaidVaultEngine.reconstructRaidZ2(available, raid.totalLength, raid.chunkSize, 4)
        assertArrayEquals("RAID reconstruct must equal ciphertext", encrypted, reconstructed)

        val decrypted = CryptoEngine.decryptPayload(reconstructed, password)
        assertArrayEquals("decrypted must equal original plaintext", data, decrypted)
    }

    @Test
    fun smallPayloadRoundTrips() = roundTrip("hello vault".toByteArray())

    @Test
    fun binaryPayloadRoundTrips() = roundTrip(ByteArray(4096) { (it * 31 % 256).toByte() })

    @Test
    fun oddLengthPayloadRoundTrips() = roundTrip(ByteArray(1023) { (it % 7).toByte() })

    @Test
    fun recoversOneMissingDataChunkFromParity() {
        val data = ByteArray(2048) { (it % 251).toByte() }
        val encrypted = CryptoEngine.encryptPayload(data, password)
        val raid = RaidVaultEngine.encodeRaidZ2WithHotSpares(encrypted, numDataChunks = 4, enableHotSpares = false)

        // Drop data chunk 2 entirely; P parity (index 4) must rebuild it.
        val available = raid.chunks
            .filter { it.chunkIndex != 2 }
            .associate { it.chunkIndex to it.data }

        val reconstructed = RaidVaultEngine.reconstructRaidZ2(available, raid.totalLength, raid.chunkSize, 4)
        val decrypted = CryptoEngine.decryptPayload(reconstructed, password)
        assertArrayEquals(data, decrypted)
    }

    @Test
    fun wrongPasswordFailsIntegrity() {
        val data = "secret".toByteArray()
        val encrypted = CryptoEngine.encryptPayload(data, password)
        try {
            CryptoEngine.decryptPayload(encrypted, "wrong password")
            throw AssertionError("decrypt with wrong password should throw")
        } catch (expected: Exception) {
            // HMAC-SHA512 verification rejects the wrong key before decryption.
        }
    }
}
