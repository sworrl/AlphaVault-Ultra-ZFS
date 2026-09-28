package com.alphasteg.pro

import com.alphasteg.pro.data.VaultVolume.Entry
import com.alphasteg.pro.data.VaultVolume.Index
import com.alphasteg.pro.net.VaultDav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VaultDavTest {

    private fun file(name: String, path: String, size: Long = 100) =
        Entry("id-$name", name, size, 12, 512, 1024, 4, 0L, 0, emptyList(), path)

    private val index = Index(
        1L,
        listOf(file("a.jpg", "/Photos"), file("note.txt", "/Docs"), file("root.pdf", "/")),
        listOf("/Photos", "/Docs")
    )

    private fun basic(user: String, pass: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

    @Test
    fun authRequiresTheSessionToken() {
        assertTrue(VaultDav.authOk(basic("vault", "tok123"), "tok123"))
        assertFalse(VaultDav.authOk(basic("vault", "wrong"), "tok123"))
        assertFalse(VaultDav.authOk(basic("someoneelse", "tok123"), "tok123"))
        assertFalse(VaultDav.authOk(null, "tok123"))
        assertFalse("empty token never authorizes", VaultDav.authOk(basic("vault", ""), ""))
    }

    @Test
    fun urlPathsNormalizeAndDecode() {
        assertEquals("/", VaultDav.urlToVaultPath("/"))
        assertEquals("/Photos", VaultDav.urlToVaultPath("/Photos/"))
        assertEquals("/Photos/a b.jpg", VaultDav.urlToVaultPath("/Photos/a%20b.jpg"))
        assertEquals("/Docs", VaultDav.urlToVaultPath("/Docs?token=x"))
    }

    @Test
    fun propfindListsFoldersAndFiles() {
        val xml = VaultDav.propfind(index, "/", depth = 1)
        assertTrue(xml.contains("<D:multistatus"))
        assertTrue(xml.contains("<D:collection/>"))          // root is a collection
        assertTrue(xml.contains("Photos"))
        assertTrue(xml.contains("Docs"))
        assertTrue(xml.contains("root.pdf"))
        assertTrue(xml.contains("<D:getcontentlength>100</D:getcontentlength>"))
    }

    @Test
    fun propfindDepthZeroHidesChildren() {
        val xml = VaultDav.propfind(index, "/", depth = 0)
        assertFalse(xml.contains("root.pdf"))
        assertTrue(xml.contains("<D:multistatus"))
    }

    @Test
    fun htmlListingLinksChildren() {
        val html = VaultDav.htmlListing(index, "/Photos")
        assertTrue(html.contains("a.jpg"))
        assertTrue(html.contains("../"))          // parent link
        assertTrue(html.startsWith("<!doctype html>"))
    }

    @Test
    fun contentTypeByExtension() {
        assertEquals("audio/flac", VaultDav.contentType("song.flac"))
        assertEquals("image/jpeg", VaultDav.contentType("x.JPG"))
        assertEquals("application/pdf", VaultDav.contentType("doc.pdf"))
        assertEquals("application/octet-stream", VaultDav.contentType("mystery"))
    }

    // ---- Range parsing: what makes seeking work for renderers and browsers ----

    @Test
    fun parsesAClosedRange() {
        assertEquals(0L..99L, VaultDav.parseRange("bytes=0-99", 1000))
        assertEquals(500L..999L, VaultDav.parseRange("bytes=500-999", 1000))
    }

    @Test
    fun openEndedRangeRunsToTheEnd() {
        assertEquals(500L..999L, VaultDav.parseRange("bytes=500-", 1000))
    }

    @Test
    fun suffixRangeTakesTheLastBytes() {
        assertEquals(900L..999L, VaultDav.parseRange("bytes=-100", 1000))
        // Asking for more than exists clamps to the whole body rather than failing.
        assertEquals(0L..999L, VaultDav.parseRange("bytes=-5000", 1000))
    }

    @Test
    fun endBeyondTheBodyIsClamped() {
        assertEquals(900L..999L, VaultDav.parseRange("bytes=900-99999", 1000))
    }

    @Test
    fun unsatisfiableOrAbsentRangesReturnNull() {
        assertNull(VaultDav.parseRange(null, 1000))
        assertNull(VaultDav.parseRange("bytes=1000-1200", 1000))  // starts past the end
        assertNull(VaultDav.parseRange("bytes=800-700", 1000))    // inverted
        assertNull(VaultDav.parseRange("items=0-10", 1000))       // not bytes
        assertNull(VaultDav.parseRange("bytes=abc-def", 1000))
        assertNull(VaultDav.parseRange("bytes=0-99", 0))          // empty body
    }

    @Test
    fun onlyTheFirstRangeOfAMultiRangeIsHonoured() {
        assertEquals(0L..49L, VaultDav.parseRange("bytes=0-49,100-149", 1000))
    }

    @Test
    fun rangeHeaderCasingIsIgnored() {
        assertEquals(0L..9L, VaultDav.parseRange("BYTES=0-9", 100))
    }
}
