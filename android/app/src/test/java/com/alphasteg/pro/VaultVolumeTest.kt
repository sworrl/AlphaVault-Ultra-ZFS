package com.alphasteg.pro

import com.alphasteg.pro.data.VaultVolume
import com.alphasteg.pro.engine.FlacCarrierEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises the whole vault-as-filesystem on the JVM using synthetic FLAC files.
 * A minimal FLAC is "fLaC" + a STREAMINFO block + fake audio frames; embedding
 * touches only the metadata, so the audio tail must stay byte-identical.
 */
class VaultVolumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val password = "master-pin-4821"

    /** Build a minimal valid FLAC with the given audio-frame bytes. */
    private fun makeFlac(audio: ByteArray): ByteArray {
        val streamInfo = ByteArray(34) { (it + 1).toByte() }
        val out = ArrayList<Byte>()
        out.addAll("fLaC".toByteArray().toList())
        out.add((0x80 or 0).toByte())          // last-block flag + STREAMINFO type
        out.add(0); out.add(0); out.add(34)     // length 34
        out.addAll(streamInfo.toList())
        out.addAll(audio.toList())
        return out.toByteArray()
    }

    private fun buildPool(): List<File> {
        val albums = listOf("Lost Society", "Ghostemane", "Misc")
        val pool = ArrayList<File>()
        var n = 0
        for (album in albums) {
            val dir = tmp.newFolder(album)
            repeat(4) {
                val f = File(dir, "track_${n}.flac")
                f.writeBytes(makeFlac(ByteArray(2000) { (n * 7 + it).toByte() }))
                pool.add(f); n++
            }
        }
        return pool
    }

    @Test
    fun vaultListRestoreRoundTrips() {
        val pool = buildPool()
        val vol = VaultVolume()
        val secret = "top secret document contents".toByteArray()

        val entry = vol.vault("secret.txt", secret, password, pool, createdAt = 1000L)
        val listed = vol.list(pool, password)
        assertEquals(1, listed.size)
        assertEquals("secret.txt", listed[0].name)

        val (name, restored) = vol.restore(entry.fileId, password, pool)
        assertEquals("secret.txt", name)
        assertArrayEquals(secret, restored)
    }

    @Test
    fun audioFramesArePreserved() {
        val dir = tmp.newFolder("One")
        val audio = ByteArray(3000) { (it % 256).toByte() }
        val f = File(dir, "a.flac").apply { writeBytes(makeFlac(audio)) }
        val pool = listOf(f)

        VaultVolume().vault("x.bin", ByteArray(500) { it.toByte() }, password, pool, 1L)

        // The audio tail (last 3000 bytes) must be identical after embedding.
        val after = f.readBytes()
        val tail = after.copyOfRange(after.size - audio.size, after.size)
        assertArrayEquals(audio, tail)
        // And the file still parses as FLAC with our blocks present.
        assertTrue(FlacCarrierEngine.isFlac(after))
        assertTrue(FlacCarrierEngine.extractAll(after).isNotEmpty())
    }

    @Test
    fun survivesAlbumDeletedFromLibrary() {
        val pool = buildPool()
        val vol = VaultVolume()
        val secret = ByteArray(5000) { (it % 251).toByte() }
        val entry = vol.vault("big.bin", secret, password, pool, 1L)

        // Simulate the user swapping out one whole album: delete those carriers.
        val survivors = pool.filterNot { it.parentFile?.name == "Lost Society" }
        assertArrayEquals(secret, vol.restore(entry.fileId, password, survivors).second)
    }

    @Test
    fun selfHealsCorruptChunk() {
        val pool = buildPool()
        val vol = VaultVolume()
        val secret = ByteArray(4096) { (it * 13 % 256).toByte() }
        val entry = vol.vault("heal.bin", secret, password, pool, 1L)

        // Corrupt one carrier's embedded chunk bytes (flip a mid-file byte).
        val victim = pool.first { FlacCarrierEngine.extractAll(it.readBytes()).any { p -> com.alphasteg.pro.data.VaultCodec.isChunkPayload(p) } }
        val bytes = victim.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        victim.writeBytes(bytes)

        // CRC catches the corrupt chunk; RAID rebuilds it from parity/mirror.
        assertArrayEquals(secret, vol.restore(entry.fileId, password, pool).second)
    }

    @Test
    fun indexGenerationTracksNewestAcrossTwoVaults() {
        val pool = buildPool()
        val vol = VaultVolume()
        vol.vault("first.txt", "one".toByteArray(), password, pool, 1L)
        vol.vault("second.txt", "two".toByteArray(), password, pool, 2L)

        val listed = vol.list(pool, password)
        assertEquals(2, listed.size)
        assertTrue(listed.any { it.name == "first.txt" })
        assertTrue(listed.any { it.name == "second.txt" })
    }

    @Test
    fun wrongPasswordHidesTheIndex() {
        val pool = buildPool()
        val vol = VaultVolume()
        vol.vault("secret.txt", "x".toByteArray(), password, pool, 1L)
        // A different password cannot decrypt the index, so it reads as empty.
        assertEquals(0, vol.list(pool, "wrong-pin").size)
    }
}
