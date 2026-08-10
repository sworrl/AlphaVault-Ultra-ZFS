package com.alphasteg.pro

import com.alphasteg.pro.data.VaultFs
import com.alphasteg.pro.data.VaultVolume.Entry
import com.alphasteg.pro.data.VaultVolume.Index
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultFsTest {

    private fun file(id: String, name: String, path: String = "/") =
        Entry(id, name, 10L, 12, 512, 1024, 4, 0L, 0, emptyList(), path)

    private fun index(entries: List<Entry> = emptyList(), folders: List<String> = emptyList()) =
        Index(1L, entries, folders)

    @Test
    fun normalizeCanonicalizesPaths() {
        assertEquals("/", VaultFs.normalize(""))
        assertEquals("/", VaultFs.normalize("///"))
        assertEquals("/Photos/2026", VaultFs.normalize("/Photos//2026/"))
        assertEquals("/Photos/2026", VaultFs.normalize("Photos/2026"))
        // ".." resolves without escaping the root
        assertEquals("/a", VaultFs.normalize("/a/b/.."))
        assertEquals("/", VaultFs.normalize("/../.."))
    }

    @Test
    fun listingSeparatesSubfoldersAndFiles() {
        val idx = index(
            entries = listOf(
                file("1", "a.jpg", "/Photos"),
                file("2", "b.jpg", "/Photos/2026"),
                file("3", "root.txt", "/")
            ),
            folders = listOf("/Docs")
        )
        val root = VaultFs.listing(idx, "/")
        assertEquals(listOf("/Docs", "/Photos"), root.folders)
        assertEquals(listOf("root.txt"), root.files.map { it.name })

        val photos = VaultFs.listing(idx, "/Photos")
        assertEquals(listOf("/Photos/2026"), photos.folders)
        assertEquals(listOf("a.jpg"), photos.files.map { it.name })
    }

    @Test
    fun mkdirCreatesEmptyFolderThatPersistsInListing() {
        val idx = VaultFs.mkdir(index(), "/Private/Keys")
        // Both the leaf and its ancestor show up in the tree.
        assertTrue(VaultFs.allFolders(idx).containsAll(listOf("/Private", "/Private/Keys")))
        assertEquals(listOf("/Private"), VaultFs.listing(idx, "/").folders)
    }

    @Test
    fun moveIsMetadataOnlyAndKeepsFileIdentityAndChunks() {
        val original = file("f1", "secret.pdf", "/")
        val idx = index(entries = listOf(original))
        val moved = VaultFs.move(idx, "f1", "/Docs/Legal")

        val e = moved.entries.single()
        assertEquals("/Docs/Legal", e.path)
        // Everything that addresses the data is untouched: same id, chunk map, sizes.
        assertEquals(original.fileId, e.fileId)
        assertEquals(original.chunkCount, e.chunkCount)
        assertEquals(original.totalLen, e.totalLen)
        assertEquals(original.numData, e.numData)
        assertEquals(original.originalSize, e.originalSize)
        // Destination folder now exists.
        assertEquals(listOf("secret.pdf"), VaultFs.listing(moved, "/Docs/Legal").files.map { it.name })
        assertTrue(VaultFs.listing(moved, "/").files.isEmpty())
    }

    @Test
    fun rmdirRefusesNonEmptyAndAllowsEmpty() {
        val idx = index(entries = listOf(file("1", "x", "/Full")), folders = listOf("/Empty"))
        try {
            VaultFs.rmdir(idx, "/Full")
            throw AssertionError("removing a non-empty folder should throw")
        } catch (expected: IllegalArgumentException) {
        }
        val after = VaultFs.rmdir(idx, "/Empty")
        assertFalse(VaultFs.allFolders(after).contains("/Empty"))
    }

    @Test
    fun moveFolderReparentsChildrenAndRejectsMoveIntoSelf() {
        val idx = index(
            entries = listOf(file("1", "a", "/Trip"), file("2", "b", "/Trip/Day1")),
            folders = listOf("/Trip/Day1")
        )
        val renamed = VaultFs.moveFolder(idx, "/Trip", "/Vacation")
        assertEquals("/Vacation", renamed.entries.first { it.fileId == "1" }.path)
        assertEquals("/Vacation/Day1", renamed.entries.first { it.fileId == "2" }.path)
        assertTrue(VaultFs.allFolders(renamed).contains("/Vacation/Day1"))
        assertFalse(VaultFs.allFolders(renamed).any { it.startsWith("/Trip") })

        try {
            VaultFs.moveFolder(idx, "/Trip", "/Trip/Day1")
            throw AssertionError("moving a folder into itself should throw")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun renameChangesNameNotFolderOrId() {
        val idx = index(entries = listOf(file("f1", "old.txt", "/Notes")))
        val out = VaultFs.rename(idx, "f1", "new.txt")
        val e = out.entries.single()
        assertEquals("new.txt", e.name)
        assertEquals("/Notes", e.path)
        assertEquals("f1", e.fileId)
    }
}
