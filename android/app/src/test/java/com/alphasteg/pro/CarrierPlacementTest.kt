package com.alphasteg.pro

import com.alphasteg.pro.data.CarrierPlacement
import com.alphasteg.pro.engine.RaidVaultEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Placement is what makes library-wide spread work now that a single file is
 * capped at a bounded number of carriers. If files did not fan out, every one
 * would pile into the same early tracks and the whole point would be lost.
 */
class CarrierPlacementTest {

    /** A library laid out like a real one: albums of tracks. */
    private fun library(albums: Int, perAlbum: Int): List<File> =
        (0 until albums).flatMap { a ->
            (0 until perAlbum).map { t -> File("/sd/MUSIC/Album%03d/%02d.flac".format(a, t)) }
        }

    private fun chunksFor(dataChunks: Int) =
        RaidVaultEngine.encodeRaidZ2WithHotSpares(ByteArray(4096), dataChunks, true).chunks

    // ---- the per-file work budget ----

    @Test
    fun oneFileNeverExceedsTheCarrierBudget() {
        for (pool in listOf(12, 100, 1_000, 6_238, 18_000)) {
            val used = RaidVaultEngine.carriersUsedFor(RaidVaultEngine.dataChunksFor(pool))
            assertTrue(
                "pool=$pool wanted $used carriers, over the budget",
                used <= RaidVaultEngine.MAX_CARRIERS_PER_FILE
            )
        }
    }

    @Test
    fun aRealisticLibraryDoesNotTriggerThousandsOfRewrites() {
        // 18,000 tracks: the literal half-the-library reading would be ~9,000
        // carriers and rewrite most of a 900 GB collection for one document.
        val chunks = RaidVaultEngine.dataChunksFor(18_000)
        val carriers = RaidVaultEngine.carriersUsedFor(chunks)
        assertTrue("got $carriers carriers", carriers in 12..64)
    }

    @Test
    fun smallLibrariesStillGetGenerousCoverage() {
        // With only 100 tracks the budget is not the binding constraint, so a file
        // still spreads over a large share of them.
        val carriers = RaidVaultEngine.carriersUsedFor(RaidVaultEngine.dataChunksFor(100))
        assertTrue("expected a decent share of 100, got $carriers", carriers >= 32)
    }

    // ---- fanning out across the vault ----

    @Test
    fun differentFilesLandOnDifferentCarriers() {
        val pool = library(albums = 40, perAlbum = 20) // 800 tracks
        val chunks = chunksFor(RaidVaultEngine.dataChunksFor(pool.size))

        val first = CarrierPlacement.assign(chunks, pool, "file-aaaa").toSet()
        val second = CarrierPlacement.assign(chunks, pool, "file-bbbb").toSet()

        val shared = first.intersect(second).size
        assertTrue(
            "two files should mostly not collide; shared $shared of ${first.size}",
            shared < first.size / 2
        )
    }

    @Test
    fun manyFilesCoverMostOfTheLibrary() {
        val pool = library(albums = 20, perAlbum = 10) // 200 tracks
        val chunks = chunksFor(RaidVaultEngine.dataChunksFor(pool.size))

        val touched = HashSet<File>()
        repeat(40) { i -> touched.addAll(CarrierPlacement.assign(chunks, pool, "doc-$i")) }

        val coverage = touched.size.toDouble() / pool.size
        assertTrue(
            "40 files should spread over most of the library, got %.0f%%".format(coverage * 100),
            coverage >= 0.5
        )
    }

    @Test
    fun placementIsStableForTheSameFile() {
        val pool = library(albums = 10, perAlbum = 10)
        val chunks = chunksFor(4)

        assertEquals(
            CarrierPlacement.assign(chunks, pool, "same-id"),
            CarrierPlacement.assign(chunks, pool, "same-id")
        )
    }

    @Test
    fun poolOrderDoesNotChangePlacement() {
        // Carriers arrive in whatever order a scan produced; placement sorts, so a
        // reshuffled pool must still resolve to the same carriers.
        val pool = library(albums = 8, perAlbum = 8)
        val chunks = chunksFor(4)

        assertEquals(
            CarrierPlacement.assign(chunks, pool, "id-1").toSet(),
            CarrierPlacement.assign(chunks.toList(), pool.shuffled(), "id-1").toSet()
        )
    }

    // ---- spreading within a file ----

    @Test
    fun aFilesChunksSpanMultipleAlbums() {
        val pool = library(albums = 12, perAlbum = 10)
        val chunks = chunksFor(4)

        val albums = CarrierPlacement.assign(chunks, pool, "id-x")
            .map { it.parentFile.name }.toSet()

        assertTrue("chunks landed in only ${albums.size} album(s)", albums.size >= 4)
    }

    @Test
    fun everyChunkGetsACarrier() {
        val pool = library(albums = 6, perAlbum = 6)
        val chunks = chunksFor(4)

        val carriers = CarrierPlacement.assign(chunks, pool, "id-y")
        assertEquals(chunks.size, carriers.size)
        assertTrue("no chunk may be left without a carrier", carriers.none { it.path.isEmpty() })
    }

    @Test
    fun aPoolSmallerThanTheChunkCountReusesCarriers() {
        // Fewer tracks than chunks: the metadata carrier can hold several payloads,
        // so this must degrade rather than fail.
        val pool = library(albums = 1, perAlbum = 3)
        val chunks = chunksFor(4)

        val carriers = CarrierPlacement.assign(chunks, pool, "id-z")
        assertEquals(chunks.size, carriers.size)
        assertTrue(carriers.all { it in pool })
    }

    @Test
    fun anEmptyPoolYieldsNothingRatherThanThrowing() {
        assertTrue(CarrierPlacement.assign(chunksFor(4), emptyList(), "id").isEmpty())
    }

    // ---- the coverage estimate shown to the user ----

    @Test
    fun coverageEstimateGrowsAndSaturates() {
        val pool = 1_000
        val perFile = 12

        val afterOne = CarrierPlacement.coverageAfter(listOf(perFile), pool)
        val afterMany = CarrierPlacement.coverageAfter(List(100) { perFile }, pool)

        assertTrue("one file should be a sliver, got $afterOne", afterOne < 0.05)
        assertTrue("100 files should be substantial, got $afterMany", afterMany > 0.5)
        assertTrue("must never exceed the whole library", afterMany <= 1.0)
    }

    @Test
    fun coverageOfAnEmptyVaultIsZero() {
        assertEquals(0.0, CarrierPlacement.coverageAfter(emptyList(), 500), 1e-9)
        assertEquals(0.0, CarrierPlacement.coverageAfter(listOf(12), 0), 1e-9)
    }
}
