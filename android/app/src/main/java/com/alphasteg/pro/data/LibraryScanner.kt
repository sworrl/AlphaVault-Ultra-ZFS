package com.alphasteg.pro.data

import java.io.File

/**
 * Reads a folder of audio as a carrier pool, without adopting it.
 *
 * This is what makes a guest session possible: point the app at any directory —
 * someone else's music, a card pulled out of a DAP, a folder copied off a NAS —
 * and get back the carriers in it. Nothing here writes, remembers, or registers
 * anything; [VaultLibrary] is the persistent counterpart and is deliberately not
 * involved, so opening a library leaves no trace of having done so.
 */
object LibraryScanner {

    /** Extensions we will consider as carriers. FLAC is the real target. */
    private val CARRIER_EXTENSIONS = setOf("flac")

    /** Folders never worth walking into; they hold no music and can be enormous. */
    private val SKIP_DIRS = setOf("Android", ".thumbnails", "cache", ".trashed", "LOST.DIR")

    /**
     * Every carrier under [root], depth-limited so a mistaken pick at the storage
     * root cannot walk the whole device. Results are sorted by path so a pool is
     * stable between scans, which matters because carrier order decides chunk
     * placement.
     */
    /**
     * Ceiling on carriers returned from one scan.
     *
     * A 900 GB library of hi-res FLAC runs to roughly 18,000 tracks, so a cap of
     * 20,000 was close enough to be dangerous - crossing it would quietly drop
     * carriers, and a pool that silently changes shape is exactly what breaks index
     * lookup. Set well clear of any real collection, and [hitLimit] reports when it
     * is reached rather than leaving the truncation invisible.
     */
    const val DEFAULT_LIMIT = 200_000

    /** True if the last-returned list was cut short by the limit. */
    fun hitLimit(found: List<File>, limit: Int = DEFAULT_LIMIT): Boolean = found.size >= limit

    fun scan(root: File, maxDepth: Int = 8, limit: Int = DEFAULT_LIMIT): List<File> {
        if (!root.isDirectory || !root.canRead()) return emptyList()
        val found = ArrayList<File>()
        walk(root, 0, maxDepth, limit, found)
        return found.sortedBy { it.absolutePath }
    }

    private fun walk(dir: File, depth: Int, maxDepth: Int, limit: Int, out: MutableList<File>) {
        if (depth > maxDepth || out.size >= limit) return
        // listFiles returns null on an unreadable directory rather than throwing.
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (out.size >= limit) return
            when {
                child.isDirectory -> {
                    if (child.name in SKIP_DIRS || child.name.startsWith(".")) continue
                    walk(child, depth + 1, maxDepth, limit, out)
                }
                isCarrier(child) -> out.add(child)
            }
        }
    }

    fun isCarrier(file: File): Boolean =
        file.isFile && file.canRead() &&
            file.extension.lowercase() in CARRIER_EXTENSIONS &&
            file.length() > 0

    /** Readable directories directly inside [dir], for the folder picker. */
    fun subfolders(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.canRead() && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }

    /** What a folder looks like before committing to it, for the picker's summary. */
    data class Preview(val carriers: Int, val totalBytes: Long) {
        val isUsable: Boolean get() = carriers > 0
    }

    fun preview(root: File, maxDepth: Int = 8): Preview {
        val carriers = scan(root, maxDepth)
        return Preview(carriers.size, carriers.sumOf { it.length() })
    }
}
