package com.alphasteg.pro

import com.alphasteg.pro.engine.RaidVaultEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The array must be sized from the library: data should land on at least half the
 * carriers, so the vault behaves like a RAID built from the whole collection
 * rather than always hammering the same few tracks.
 */
class RaidSpreadTest {

    private fun coverage(poolSize: Int): Double {
        val n = RaidVaultEngine.dataChunksFor(poolSize)
        return RaidVaultEngine.carriersUsedFor(n).toDouble() / poolSize
    }

    @Test
    fun usesAtLeastHalfOfARealisticLibrary() {
        for (pool in listOf(12, 20, 50, 100, 200, 500, 1000)) {
            assertTrue(
                "pool=$pool only covered ${"%.1f".format(coverage(pool) * 100)}%",
                coverage(pool) >= 0.5
            )
        }
    }

    @Test
    fun neverAsksForMoreCarriersThanExist() {
        for (pool in listOf(12, 20, 50, 100, 200, 500, 1000, 2000)) {
            val used = RaidVaultEngine.carriersUsedFor(RaidVaultEngine.dataChunksFor(pool))
            assertTrue("pool=$pool wanted $used carriers", used <= pool)
        }
    }

    @Test
    fun scalesWithTheLibrary() {
        // A bigger library must mean a wider array, not the same fixed shape.
        assertTrue(RaidVaultEngine.dataChunksFor(200) > RaidVaultEngine.dataChunksFor(50))
        assertTrue(RaidVaultEngine.dataChunksFor(50) > RaidVaultEngine.dataChunksFor(20))
    }

    @Test
    fun exactCoverageForRoundLibraries() {
        // 100 carriers: 23 data + 2 parity = 25, mirrored to 50 = exactly half.
        assertEquals(23, RaidVaultEngine.dataChunksFor(100))
        assertEquals(50, RaidVaultEngine.carriersUsedFor(23))
        // 200 carriers: 48 + 2 = 50, mirrored to 100.
        assertEquals(48, RaidVaultEngine.dataChunksFor(200))
        assertEquals(100, RaidVaultEngine.carriersUsedFor(48))
    }

    @Test
    fun smallLibrariesFallBackToTheBaselineShape() {
        // Twelve carriers is exactly the baseline 4+2 mirrored, i.e. all of them.
        assertEquals(RaidVaultEngine.DEFAULT_DATA_CHUNKS, RaidVaultEngine.dataChunksFor(12))
        assertEquals(12, RaidVaultEngine.carriersUsedFor(RaidVaultEngine.dataChunksFor(12)))
    }

    @Test
    fun tinyLibrariesStillProduceAValidArray() {
        // Below the baseline there are not enough carriers for 4+2 mirrored, but
        // RAID-Z2 still needs two data chunks to be meaningful.
        for (pool in listOf(1, 2, 4, 8)) {
            val n = RaidVaultEngine.dataChunksFor(pool)
            assertTrue("pool=$pool gave n=$n", n >= 2)
        }
    }

    @Test
    fun anEmptyPoolDoesNotBlowUp() {
        assertEquals(RaidVaultEngine.DEFAULT_DATA_CHUNKS, RaidVaultEngine.dataChunksFor(0))
        assertEquals(RaidVaultEngine.DEFAULT_DATA_CHUNKS, RaidVaultEngine.dataChunksFor(-5))
    }

    @Test
    fun hugeLibrariesAreBoundedRatherThanUnbounded() {
        // Coverage yields to a ceiling so a 50k-track library does not turn one
        // save into tens of thousands of embeds.
        val n = RaidVaultEngine.dataChunksFor(50_000)
        assertTrue("chunk count must stay bounded, got $n", n <= 512)
    }

    @Test
    fun theArrayItProducesStillReconstructs() {
        // A wider array must still be genuine RAID-Z2: lose any two data chunks
        // and the file must come back byte-for-byte.
        val data = ByteArray(9_000) { (it * 13).toByte() }
        val n = RaidVaultEngine.dataChunksFor(100)
        val encoded = RaidVaultEngine.encodeRaidZ2WithHotSpares(data, n, true)

        val surviving = encoded.chunks
            .filter { it.chunkIndex != 0 && it.chunkIndex != 1 }
            .associate { it.chunkIndex to it.data }
        val back = RaidVaultEngine.reconstructRaidZ2(surviving, encoded.totalLength, encoded.chunkSize, n)

        assertEquals(data.toList(), back.toList())
    }
}
