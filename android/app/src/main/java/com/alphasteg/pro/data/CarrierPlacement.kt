package com.alphasteg.pro.data

import com.alphasteg.pro.engine.RaidVaultEngine
import java.io.File

/**
 * Decides which carriers a file's chunks go into.
 *
 * Two things matter, and they pull in opposite directions.
 *
 * Data should end up spread across the library rather than concentrated in a few
 * tracks - a handful of FLACs that are all slightly odd is exactly the pattern
 * worth not having. But a carrier embed rewrites the whole FLAC, so "spread" has
 * to be counted across the *vault*, not per file. On a 900 GB library of ~18,000
 * tracks, asking one file to touch half the carriers means rewriting ~880 GB and
 * several hours of SD-card wear for one document.
 *
 * So each file touches a bounded number of carriers, and placement is *rotated* by
 * the file's own id. Without that rotation the assignment is deterministic from the
 * front of the pool and every file piles into the same early tracks; with it, files
 * fan out, and a library accumulates coverage as files are added. [coverageAfter]
 * says how far that has got.
 */
object CarrierPlacement {

    /**
     * Carriers for [chunks], drawn from [pool] and rotated by [fileId].
     *
     * Chunks are handed out across folders first, so a file's data does not sit in
     * one album, and primaries are kept away from their hot spares for the same
     * reason. When the pool is smaller than the chunk count, carriers are reused
     * rather than failing - the caller has already checked that the chosen carrier
     * method allows it.
     */
    fun assign(
        chunks: List<RaidVaultEngine.VaultChunkInfo>,
        pool: List<File>,
        fileId: String
    ): List<File> {
        if (pool.isEmpty()) return emptyList()

        val byFolder = LinkedHashMap<String, ArrayDeque<File>>()
        for (f in pool.sortedBy { it.absolutePath }) {
            byFolder.getOrPut(f.parentFile?.name ?: "") { ArrayDeque() }.add(f)
        }
        val folderNames = byFolder.keys.toList()
        val folderCount = folderNames.size.coerceAtLeast(1)

        // Rotate where this file starts. Deterministic from the id, so a restore
        // does not depend on it, but different files begin in different places.
        val rotation = rotationFor(fileId, folderCount)
        // Within a folder, also skip ahead, so two files starting in the same
        // folder do not both take its first track.
        val depth = rotationFor("$fileId-depth", pool.size)

        for ((i, name) in folderNames.withIndex()) {
            val queue = byFolder[name] ?: continue
            if (queue.isEmpty()) continue
            val skip = ((depth + i) % queue.size)
            repeat(skip) { queue.addLast(queue.removeFirst()) }
        }

        val primaryCount = chunks.count { !it.isHotSpare }.coerceAtLeast(1)

        fun pullPreferring(folder: Int): File? {
            for (off in 0 until folderCount) {
                val q = byFolder[folderNames[(folder + off) % folderCount]]
                if (q != null && q.isNotEmpty()) return q.removeFirst()
            }
            return null
        }

        val result = arrayOfNulls<File>(chunks.size)
        chunks.forEachIndexed { i, c ->
            val idx = c.chunkIndex
            // Hot spares deliberately start one folder further along than the
            // primaries they mirror, so losing an album cannot take both.
            val preferred = if (idx < primaryCount) (rotation + idx) % folderCount
            else (rotation + (idx - primaryCount) + 1) % folderCount
            result[i] = pullPreferring(preferred)
        }

        val used = result.filterNotNull()
        val fallback = used.ifEmpty { pool }
        for (i in result.indices) if (result[i] == null) result[i] = fallback[i % fallback.size]
        return result.map { it!! }
    }

    /** Stable non-negative rotation in [0, modulo) derived from [key]. */
    private fun rotationFor(key: String, modulo: Int): Int {
        if (modulo <= 1) return 0
        var h = 0x811c9dc5.toInt()
        for (c in key) {
            h = h xor c.code
            h *= 0x01000193
        }
        return ((h.toLong() and 0xFFFFFFFFL) % modulo).toInt()
    }

    /**
     * Roughly how much of a [poolSize] library holds vault data after storing
     * files of [chunkCounts] chunks each, as a fraction.
     *
     * An estimate, not a measurement: it assumes placement keeps fanning out, which
     * is what the rotation is for. Used for the stats line, so the user can see the
     * library filling in rather than having to take it on faith.
     */
    fun coverageAfter(chunkCounts: List<Int>, poolSize: Int): Double {
        if (poolSize <= 0) return 0.0
        val placements = chunkCounts.sum()
        // Coupon-collector style: each placement is unlikely to be somewhere new
        // once most of the library is already used.
        val expectedDistinct = poolSize * (1.0 - Math.pow(1.0 - 1.0 / poolSize, placements.toDouble()))
        return (expectedDistinct / poolSize).coerceIn(0.0, 1.0)
    }
}
