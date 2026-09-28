package com.alphasteg.pro

import com.alphasteg.pro.engine.ArraySource
import com.alphasteg.pro.engine.CryptoEngine
import com.alphasteg.pro.engine.RaidVaultEngine
import com.alphasteg.pro.engine.StripedSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ByteSourceTest {

    private fun bytes(n: Int, seed: Int = 0) = ByteArray(n) { (it + seed).toByte() }

    // ---- StripedSource ----

    @Test
    fun readsAcrossChunkBoundaries() {
        val chunks = listOf(bytes(10, 0), bytes(10, 10), bytes(10, 20))
        val src = StripedSource(chunks, 10, 30)

        assertArrayEquals(bytes(30, 0), src.slice(0, 30))
        // A read starting mid-chunk and ending in a later one.
        assertArrayEquals(bytes(15, 5), src.slice(5, 15))
        assertArrayEquals(bytes(1, 29), src.slice(29, 1))
    }

    @Test
    fun logicalSizeIgnoresChunkPadding() {
        // The last chunk is mostly padding, as after a RAID split.
        val chunks = listOf(bytes(10, 0), bytes(10, 10))
        val src = StripedSource(chunks, 10, 14)

        assertEquals(14L, src.size)
        assertArrayEquals(bytes(14, 0), src.slice(0, 14))
        assertTrue(runCatching { src.slice(0, 15) }.isFailure)
    }

    @Test
    fun refusesReadsOutsideTheSource() {
        val src = StripedSource(listOf(bytes(8)), 8, 8)
        assertTrue(runCatching { src.slice(-1, 2) }.isFailure)
        assertTrue(runCatching { src.slice(7, 4) }.isFailure)
    }

    @Test
    fun readIntMatchesBigEndian() {
        val src = ArraySource(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x7F.toByte()))
        assertEquals(0x01020304, src.readInt(0))
    }

    @Test
    fun arraySourceAndStripedSourceAgree() {
        val whole = bytes(64, 3)
        val striped = StripedSource(List(4) { i -> whole.copyOfRange(i * 16, i * 16 + 16) }, 16, 64)
        val array = ArraySource(whole)
        for (at in listOf(0L, 1L, 15L, 16L, 31L, 48L)) {
            val len = minOf(17, (64 - at).toInt())
            assertArrayEquals("at=$at", array.slice(at, len), striped.slice(at, len))
        }
    }

    // ---- decrypting straight out of RAID chunks ----

    /** Split like the vault does, so the chunks look the way a restore finds them. */
    private fun chunksOf(payload: ByteArray, numData: Int) =
        RaidVaultEngine.encodeRaidZ2WithHotSpares(payload, numData, true)

    @Test
    fun decryptsFromChunksWithoutRebuildingTheBlob() {
        val plain = bytes(200_000, 7)
        val enc = CryptoEngine.encryptPayload(plain, "benchPassword1")
        val raid = chunksOf(enc, 4)

        val source = RaidVaultEngine.sourceIfIntact(
            raid.chunks.associate { it.chunkIndex to it.data },
            raid.totalLength, raid.chunkSize, 4
        )
        assertNotNull("all chunks present, so no rebuild is needed", source)

        val out = ByteArrayOutputStream()
        CryptoEngine.decryptTo(source!!, "benchPassword1", out)
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun aMissingDataChunkFallsBackToRepair() {
        val enc = CryptoEngine.encryptPayload(bytes(50_000, 2), "benchPassword1")
        val raid = chunksOf(enc, 4)

        // Drop data chunk 1 and its hot-spare mirror, so only parity can recover it.
        val mirrorOffset = 4 + 2
        val crippled = raid.chunks
            .filter { it.chunkIndex != 1 && it.chunkIndex != 1 + mirrorOffset }
            .associate { it.chunkIndex to it.data }

        assertNull(
            "a lost data chunk must not be readable as a plain concatenation",
            RaidVaultEngine.sourceIfIntact(crippled, raid.totalLength, raid.chunkSize, 4)
        )
        // The repair path still produces the same bytes.
        val rebuilt = RaidVaultEngine.reconstructRaidZ2(crippled, raid.totalLength, raid.chunkSize, 4)
        assertArrayEquals(enc, rebuilt)
    }

    @Test
    fun aMirroredChunkCountsAsPresent() {
        val enc = CryptoEngine.encryptPayload(bytes(30_000, 5), "benchPassword1")
        val raid = chunksOf(enc, 4)

        // Data chunk 2 is gone but its hot spare survives.
        val viaMirror = raid.chunks
            .filter { it.chunkIndex != 2 }
            .associate { it.chunkIndex to it.data }

        val source = RaidVaultEngine.sourceIfIntact(viaMirror, raid.totalLength, raid.chunkSize, 4)
        assertNotNull("the hot-spare mirror should stand in for chunk 2", source)

        val out = ByteArrayOutputStream()
        CryptoEngine.decryptTo(source!!, "benchPassword1", out)
        assertArrayEquals(bytes(30_000, 5), out.toByteArray())
    }

    @Test
    fun tamperingWithAChunkIsStillCaught() {
        val enc = CryptoEngine.encryptPayload(bytes(80_000, 9), "benchPassword1")
        val raid = chunksOf(enc, 4)
        val map = raid.chunks.associate { it.chunkIndex to it.data.copyOf() }.toMutableMap()
        // Flip a bit inside the first data chunk.
        map[0]!![100] = (map[0]!![100].toInt() xor 0x01).toByte()

        val source = RaidVaultEngine.sourceIfIntact(map, raid.totalLength, raid.chunkSize, 4)!!
        val out = ByteArrayOutputStream()
        assertTrue(
            "the outer HMAC must reject a corrupted chunk",
            runCatching { CryptoEngine.decryptTo(source, "benchPassword1", out) }.isFailure
        )
        assertEquals(0, out.size())
    }

    @Test
    fun streamedFromChunksMatchesTheInMemoryPath() {
        val plain = bytes(CryptoEngine.FRAME_SIZE + 4321, 11)
        val enc = CryptoEngine.encryptPayload(plain, "benchPassword1")
        val raid = chunksOf(enc, 6)

        val source = RaidVaultEngine.sourceIfIntact(
            raid.chunks.associate { it.chunkIndex to it.data },
            raid.totalLength, raid.chunkSize, 6
        )!!
        val streamed = ByteArrayOutputStream()
        CryptoEngine.decryptTo(source, "benchPassword1", streamed)

        assertArrayEquals(CryptoEngine.decryptPayload(enc, "benchPassword1"), streamed.toByteArray())
        assertArrayEquals(plain, streamed.toByteArray())
    }
}
