package com.alphasteg.pro

import com.alphasteg.pro.data.LibraryScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun flac(parent: File, name: String, bytes: Int = 16): File =
        File(parent, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }

    @Test
    fun findsCarriersNestedInAlbumFolders() {
        val root = tmp.newFolder("Music")
        flac(File(root, "Artist/Album"), "01.flac")
        flac(File(root, "Artist/Album"), "02.flac")
        flac(File(root, "Other"), "03.flac")

        val found = LibraryScanner.scan(root)

        assertEquals(3, found.size)
        assertTrue(found.all { it.extension == "flac" })
    }

    @Test
    fun ignoresNonCarrierFiles() {
        val root = tmp.newFolder("Music")
        flac(root, "keep.flac")
        File(root, "cover.jpg").writeBytes(ByteArray(8))
        File(root, "notes.txt").writeText("hello")
        File(root, "song.mp3").writeBytes(ByteArray(8))

        val found = LibraryScanner.scan(root)

        assertEquals(1, found.size)
        assertEquals("keep.flac", found.first().name)
    }

    @Test
    fun ignoresEmptyFiles() {
        val root = tmp.newFolder("Music")
        File(root, "zero.flac").createNewFile()
        flac(root, "real.flac")

        assertEquals(listOf("real.flac"), LibraryScanner.scan(root).map { it.name })
    }

    @Test
    fun matchesExtensionCaseInsensitively() {
        val root = tmp.newFolder("Music")
        flac(root, "SHOUTY.FLAC")
        assertEquals(1, LibraryScanner.scan(root).size)
    }

    @Test
    fun skipsHiddenAndSystemDirectories() {
        val root = tmp.newFolder("Music")
        flac(File(root, "Android/data/junk"), "hidden.flac")
        flac(File(root, ".thumbnails"), "thumb.flac")
        flac(File(root, ".secret"), "dot.flac")
        flac(root, "visible.flac")

        assertEquals(listOf("visible.flac"), LibraryScanner.scan(root).map { it.name })
    }

    @Test
    fun respectsTheDepthLimit() {
        val root = tmp.newFolder("Music")
        flac(File(root, "a/b/c/d"), "deep.flac")
        flac(root, "shallow.flac")

        assertEquals(2, LibraryScanner.scan(root, maxDepth = 8).size)
        assertEquals("only the shallow one is within depth 1", 1, LibraryScanner.scan(root, maxDepth = 1).size)
    }

    @Test
    fun resultsAreStablyOrdered() {
        val root = tmp.newFolder("Music")
        flac(File(root, "B"), "2.flac")
        flac(File(root, "A"), "1.flac")

        // Carrier order decides chunk placement, so two scans must agree.
        assertEquals(LibraryScanner.scan(root), LibraryScanner.scan(root))
        assertEquals(listOf("1.flac", "2.flac"), LibraryScanner.scan(root).map { it.name })
    }

    @Test
    fun honoursTheResultLimit() {
        val root = tmp.newFolder("Music")
        repeat(10) { flac(root, "t$it.flac") }
        assertEquals(4, LibraryScanner.scan(root, limit = 4).size)
    }

    @Test
    fun aFileOrMissingPathScansToNothing() {
        val file = tmp.newFile("notafolder.flac")
        assertTrue(LibraryScanner.scan(file).isEmpty())
        assertTrue(LibraryScanner.scan(File(tmp.root, "does-not-exist")).isEmpty())
    }

    @Test
    fun previewReportsCountAndSize() {
        val root = tmp.newFolder("Music")
        flac(root, "a.flac", bytes = 100)
        flac(root, "b.flac", bytes = 250)

        val preview = LibraryScanner.preview(root)

        assertEquals(2, preview.carriers)
        assertEquals(350L, preview.totalBytes)
        assertTrue(preview.isUsable)
    }

    @Test
    fun previewOfAFolderWithNoMusicIsNotUsable() {
        val root = tmp.newFolder("Empty")
        assertFalse(LibraryScanner.preview(root).isUsable)
    }

    @Test
    fun subfoldersListsOnlyReadableVisibleDirectories() {
        val root = tmp.newFolder("Music")
        File(root, "Albums").mkdirs()
        File(root, "Live").mkdirs()
        File(root, ".hidden").mkdirs()
        flac(root, "loose.flac")

        assertEquals(listOf("Albums", "Live"), LibraryScanner.subfolders(root).map { it.name })
    }
}
