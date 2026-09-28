package com.alphasteg.pro

import com.alphasteg.pro.engine.RaidVaultEngine
import org.junit.Assert.assertArrayEquals
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
    fun coversHalfOfALibrarySmallEnoughToAffordIt() {
        // Up to the point where the per-file carrier budget bites, a single file
        // still spreads over at least half the library.
        val affordable = RaidVaultEngine.MAX_CARRIERS_PER_FILE * 2
        for (pool in listOf(12, 20, 50, 100, affordable)) {
            assertTrue(
                "pool=$pool only covered ${"%.1f".format(coverage(pool) * 100)}%",
                coverage(pool) >= 0.5
            )
        }
    }

    @Test
    fun aboveThatBudgetPerFileCoverageDeliberatelyFalls() {
        // Beyond it, coverage yields: a carrier embed rewrites the whole FLAC, so
        // half of an 18,000-track library would be ~9,000 rewrites for one file.
        // Library-wide spread is achieved by rotating placement per file instead;
        // see CarrierPlacementTest.
        for (pool in listOf(1_000, 6_238, 18_000)) {
            val used = RaidVaultEngine.carriersUsedFor(RaidVaultEngine.dataChunksFor(pool))
            assertTrue(
                "pool=$pool touched $used carriers, over the budget",
                used <= RaidVaultEngine.MAX_CARRIERS_PER_FILE
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
    fun exactShapeForRoundLibraries() {
        // 100 carriers: half is 50, under the 64 budget, so 23 data + 2 parity = 25,
        // mirrored to 50 - exactly half the library.
        assertEquals(23, RaidVaultEngine.dataChunksFor(100))
        assertEquals(50, RaidVaultEngine.carriersUsedFor(23))
        // 200 carriers: half would be 100, over the budget, so it settles at 64.
        assertEquals(30, RaidVaultEngine.dataChunksFor(200))
        assertEquals(64, RaidVaultEngine.carriersUsedFor(30))
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
    fun tailPaddingSurvivesARoundTrip() {
        // Sizes that do not divide evenly, so the last chunk is part padding. The
        // padded copy of the whole payload was removed, so this is the case most
        // likely to break if the slicing arithmetic is wrong.
        for (size in listOf(1, 7, 999, 1001, 4095, 4097)) {
            for (n in listOf(2, 4, 7)) {
                val data = ByteArray(size) { (it * 17 + n).toByte() }
                val encoded = RaidVaultEngine.encodeRaidZ2WithHotSpares(data, n, true)
                val all = encoded.chunks.associate { it.chunkIndex to it.data }
                val back = RaidVaultEngine.reconstructRaidZ2(all, encoded.totalLength, encoded.chunkSize, n)
                assertEquals("size=$size n=$n", data.toList(), back.toList())
            }
        }
    }

    @Test
    fun hotSparesMirrorTheirOriginalsExactly() {
        val data = ByteArray(5000) { (it * 3).toByte() }
        val encoded = RaidVaultEngine.encodeRaidZ2WithHotSpares(data, 4, true)

        val primaries = encoded.chunks.filter { !it.isHotSpare }
        val spares = encoded.chunks.filter { it.isHotSpare }
        assertEquals(primaries.size, spares.size)

        // Spares share their original's array rather than duplicating it, which is
        // only sound because chunk data is read-only from here on.
        primaries.forEachIndexed { i, original ->
            assertArrayEquals("spare $i", original.data, spares[i].data)
        }
    }

    @Test
    fun aFileIsRecoverableFromSparesAlone() {
        // Losing every primary chunk still leaves the mirrors, which is the point
        // of hot spares and would break if sharing had aliased the wrong array.
        val data = ByteArray(3000) { (it * 11).toByte() }
        val encoded = RaidVaultEngine.encodeRaidZ2WithHotSpares(data, 4, true)
        val sparesOnly = encoded.chunks.filter { it.isHotSpare }.associate { it.chunkIndex to it.data }

        val back = RaidVaultEngine.reconstructRaidZ2(sparesOnly, encoded.totalLength, encoded.chunkSize, 4)
        assertEquals(data.toList(), back.toList())
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
