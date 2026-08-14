package com.alphasteg.pro

import com.alphasteg.pro.data.VaultVolume.Entry
import com.alphasteg.pro.data.VaultVolume.Index
import com.alphasteg.pro.net.RemoteVault
import com.alphasteg.pro.net.VaultDav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client half must understand exactly what the server half emits, so these
 * generate listings with [VaultDav] and read them back with [RemoteVault] rather
 * than asserting against hand-written JSON that could drift from reality.
 */
class RemoteVaultTest {

    private fun entry(name: String, path: String, size: Long = 100) = Entry(
        fileId = "id-$name",
        name = name,
        originalSize = size,
        chunkCount = 12,
        chunkSize = 16,
        totalLen = size.toInt(),
        numData = 4,
        createdAt = 0L,
        path = path
    )

    private val index = Index(
        generation = 1,
        entries = listOf(
            entry("notes.txt", "/", 42),
            entry("a.jpg", "/Photos", 2048),
            entry("b.jpg", "/Photos", 4096)
        ),
        folders = listOf("/Photos", "/Papers")
    )

    @Test
    fun readsBackWhatTheServerEmitsForTheRoot() {
        val items = RemoteVault.parseListing(VaultDav.jsonListing(index, "/"))

        val names = items.map { it.name }
        assertTrue("folders should be listed", names.contains("Photos"))
        assertTrue(names.contains("Papers"))
        assertTrue("files should be listed", names.contains("notes.txt"))
    }

    @Test
    fun distinguishesFoldersFromFiles() {
        val items = RemoteVault.parseListing(VaultDav.jsonListing(index, "/"))

        assertTrue(items.first { it.name == "Photos" }.isFolder)
        assertFalse(items.first { it.name == "notes.txt" }.isFolder)
    }

    @Test
    fun carriesFileSizes() {
        val items = RemoteVault.parseListing(VaultDav.jsonListing(index, "/Photos"))

        assertEquals(2048L, items.first { it.name == "a.jpg" }.size)
        assertEquals(4096L, items.first { it.name == "b.jpg" }.size)
    }

    @Test
    fun carriesUsablePathsForRecursion() {
        val items = RemoteVault.parseListing(VaultDav.jsonListing(index, "/"))

        assertEquals("/Photos", items.first { it.name == "Photos" }.path)
        // Descending with that path must produce the folder's children.
        val children = RemoteVault.parseListing(VaultDav.jsonListing(index, "/Photos"))
        assertEquals(setOf("a.jpg", "b.jpg"), children.map { it.name }.toSet())
    }

    @Test
    fun foldersSortBeforeFiles() {
        val items = RemoteVault.parseListing(VaultDav.jsonListing(index, "/"))
        val firstFile = items.indexOfFirst { !it.isFolder }
        val lastFolder = items.indexOfLast { it.isFolder }
        assertTrue("folders must come first", lastFolder < firstFile)
    }

    @Test
    fun namesWithSpacesAndSymbolsSurviveTheRoundTrip() {
        val awkward = Index(
            generation = 1,
            entries = listOf(
                entry("holiday photos & more.jpg", "/"),
                entry("quote\"inside.txt", "/"),
                entry("back\\slash.txt", "/")
            ),
            folders = emptyList()
        )
        val names = RemoteVault.parseListing(VaultDav.jsonListing(awkward, "/")).map { it.name }.toSet()

        assertEquals(
            setOf("holiday photos & more.jpg", "quote\"inside.txt", "back\\slash.txt"),
            names
        )
    }

    @Test
    fun anEmptyFolderListsNothing() {
        assertTrue(RemoteVault.parseListing(VaultDav.jsonListing(index, "/Papers")).isEmpty())
    }

    @Test
    fun rubbishInputYieldsNothingRatherThanThrowing() {
        assertTrue(RemoteVault.parseListing("").isEmpty())
        assertTrue(RemoteVault.parseListing("<html>nope</html>").isEmpty())
        assertTrue(RemoteVault.parseListing("{\"items\":").isEmpty())
        assertTrue(RemoteVault.parseListing("{\"items\":[{\"nope\":1}]}").isEmpty())
        assertTrue(RemoteVault.parseListing("{}").isEmpty())
    }
}
