package com.alphasteg.pro

import com.alphasteg.pro.net.CastGrants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastGrantsTest {

    /** Grants with a clock we control, so expiry is testable without sleeping. */
    private class Clock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun issuedTokenResolvesToItsFile() {
        val grants = CastGrants()
        val g = grants.issue("file-123", "song.flac", "audio/flac")
        val back = grants.resolve(g.token)
        assertEquals("file-123", back?.fileId)
        assertEquals("song.flac", back?.name)
        assertEquals("audio/flac", back?.contentType)
    }

    @Test
    fun unknownTokenResolvesToNothing() {
        val grants = CastGrants()
        grants.issue("file-123", "song.flac", "audio/flac")
        assertNull(grants.resolve("not-a-real-token"))
        assertNull(grants.resolve(""))
    }

    @Test
    fun tokensAreLongAndDistinct() {
        val grants = CastGrants()
        val seen = HashSet<String>()
        repeat(50) {
            val t = grants.issue("f$it", "n", "audio/flac").token
            // 32 random bytes, base64url without padding.
            assertTrue("token too short to resist guessing: ${t.length}", t.length >= 42)
            assertTrue("must be URL-safe", t.none { c -> c == '+' || c == '/' || c == '=' })
            assertTrue("duplicate token issued", seen.add(t))
        }
    }

    @Test
    fun aGrantCoversOnlyItsOwnFile() {
        val grants = CastGrants()
        val a = grants.issue("file-a", "a.flac", "audio/flac")
        val b = grants.issue("file-b", "b.flac", "audio/flac")
        assertEquals("file-a", grants.resolve(a.token)?.fileId)
        assertEquals("file-b", grants.resolve(b.token)?.fileId)
        assertNotEquals(a.token, b.token)
    }

    @Test
    fun expiredGrantsStopResolving() {
        val clock = Clock()
        val grants = CastGrants(clock)
        val g = grants.issue("file-123", "song.flac", "audio/flac", ttlMs = 1000)
        assertEquals("file-123", grants.resolve(g.token)?.fileId)

        clock.now += 999
        assertEquals("still inside the window", "file-123", grants.resolve(g.token)?.fileId)

        clock.now += 2
        assertNull("past expiry the capability is gone", grants.resolve(g.token))
        assertEquals("and it is dropped, not merely hidden", 0, grants.activeCount())
    }

    @Test
    fun revokeAllClearsEverythingAndZeroesPlaintext() {
        val grants = CastGrants()
        val g = grants.issue("file-123", "song.flac", "audio/flac")
        val plaintext = ByteArray(32) { 0x41 }
        grants.cache(g.token, plaintext)
        assertArrayEquals(ByteArray(32) { 0x41 }, grants.cached(g.token))

        grants.revokeAll()

        assertNull(grants.resolve(g.token))
        assertNull(grants.cached(g.token))
        // The buffer we handed over must be wiped, not just dereferenced.
        assertArrayEquals("cached plaintext must be zeroed on revoke", ByteArray(32), plaintext)
    }

    @Test
    fun revokingOneGrantWipesOnlyThatBuffer() {
        val grants = CastGrants()
        val a = grants.issue("file-a", "a.flac", "audio/flac")
        val b = grants.issue("file-b", "b.flac", "audio/flac")
        val bufA = ByteArray(8) { 1 }
        val bufB = ByteArray(8) { 2 }
        grants.cache(a.token, bufA)
        grants.cache(b.token, bufB)

        grants.revoke(a.token)

        assertArrayEquals(ByteArray(8), bufA)
        assertArrayEquals(ByteArray(8) { 2 }, bufB)
        assertEquals("file-b", grants.resolve(b.token)?.fileId)
    }

    @Test
    fun cachingAgainstAnUnknownTokenIsIgnored() {
        val grants = CastGrants()
        grants.cache("bogus", ByteArray(4))
        assertNull(grants.cached("bogus"))
    }

    @Test
    fun purgeDropsOnlyTheExpired() {
        val clock = Clock()
        val grants = CastGrants(clock)
        grants.issue("short", "a", "audio/flac", ttlMs = 100)
        val long = grants.issue("long", "b", "audio/flac", ttlMs = 10_000)

        clock.now += 500
        grants.purgeExpired()

        assertEquals(1, grants.activeCount())
        assertEquals("long", grants.resolve(long.token)?.fileId)
    }

    // ---- URL shape ----

    @Test
    fun readsTheTokenOutOfACastPath() {
        assertEquals("abc123", CastGrants.tokenFromPath("/cast/abc123/song.flac"))
        assertEquals("abc123", CastGrants.tokenFromPath("/cast/abc123"))
        assertEquals("abc123", CastGrants.tokenFromPath("/cast/abc123/song.flac?x=1"))
    }

    @Test
    fun nonCastPathsAreNotTreatedAsCapabilities() {
        assertNull(CastGrants.tokenFromPath("/"))
        assertNull(CastGrants.tokenFromPath("/Photos/a.jpg"))
        assertNull(CastGrants.tokenFromPath("/cast/"))
        // A path that merely mentions cast deeper down must not qualify.
        assertNull(CastGrants.tokenFromPath("/files/cast/abc"))
    }
}
