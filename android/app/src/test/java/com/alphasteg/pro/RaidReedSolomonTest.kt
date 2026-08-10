package com.alphasteg.pro

import com.alphasteg.pro.engine.GaloisField
import com.alphasteg.pro.engine.RaidVaultEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the parity is real RAID-6 Reed-Solomon: any two data chunks can be
 * rebuilt from P and Q alone, with no hot-spare mirror in play.
 */
class RaidReedSolomonTest {

    @Test fun galoisFieldSanity() {
        // Every nonzero element times its inverse is 1.
        for (a in 1..255) assertEquals(1, GaloisField.mul(a, GaloisField.inv(a)))
        // Distributivity spot check.
        assertEquals(
            GaloisField.mul(7, 11) xor GaloisField.mul(7, 200),
            GaloisField.mul(7, 11 xor 200)
        )
    }

    private val payload = ByteArray(4001) { (it * 37 + 5).toByte() }

    private fun encodeNoMirror() =
        RaidVaultEngine.encodeRaidZ2WithHotSpares(payload, numDataChunks = 4, enableHotSpares = false)

    @Test fun recoversAnyTwoDataChunksFromParity() {
        val raid = encodeNoMirror()
        val all = raid.chunks.associate { it.chunkIndex to it.data }
        // Try every pair of data chunks (0..3) dropped, keeping P(4) and Q(5).
        for (x in 0 until 4) for (y in (x + 1) until 4) {
            val avail = all.filterKeys { it != x && it != y }
            val restored = RaidVaultEngine.reconstructRaidZ2(avail, raid.totalLength, raid.chunkSize, 4)
            assertArrayEquals("dropping data $x and $y", payload, restored)
        }
    }

    @Test fun recoversDataChunkFromQWhenPIsGone() {
        val raid = encodeNoMirror()
        val all = raid.chunks.associate { it.chunkIndex to it.data }
        // Drop data chunk 1 AND the P parity (index 4); only Q remains for recovery.
        val avail = all.filterKeys { it != 1 && it != 4 }
        val restored = RaidVaultEngine.reconstructRaidZ2(avail, raid.totalLength, raid.chunkSize, 4)
        assertArrayEquals(payload, restored)
    }

    @Test fun recoversSingleChunkFromP() {
        val raid = encodeNoMirror()
        val all = raid.chunks.associate { it.chunkIndex to it.data }
        val avail = all.filterKeys { it != 2 } // drop one data chunk, P and Q present
        val restored = RaidVaultEngine.reconstructRaidZ2(avail, raid.totalLength, raid.chunkSize, 4)
        assertArrayEquals(payload, restored)
    }
}
