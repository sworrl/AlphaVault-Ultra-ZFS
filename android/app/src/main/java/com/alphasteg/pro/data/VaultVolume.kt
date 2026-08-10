package com.alphasteg.pro.data

import com.alphasteg.pro.engine.CryptoEngine
import com.alphasteg.pro.engine.FlacCarrierEngine
import com.alphasteg.pro.engine.RaidVaultEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The vault as a self-contained filesystem living inside the FLAC library.
 *
 * There is no app-private database. Everything needed to find and rebuild a
 * vaulted file lives in the carriers themselves:
 *
 *  - Data chunks (AVC1 blocks): each vaulted file is cascade-encrypted, split
 *    into RAID-Z2 chunks (data + P/Q parity + hot-spare mirror), and each chunk
 *    is embedded in a carrier, spread across albums.
 *  - The index (AVIX block): an encrypted, CRC-checksummed table of contents for
 *    the whole vault. It carries a generation counter and is replicated into
 *    several carriers across different albums, like a GPT header or a ZFS
 *    uberblock. The highest-generation replica that checksums and decrypts wins.
 *
 * Integrity and self-healing: every chunk carries a CRC32. On restore, a chunk
 * that fails its checksum or is missing (album swapped out) is treated as absent
 * and rebuilt from parity or its mirror. `scrub` re-embeds anything it had to
 * rebuild, healing the volume.
 */
class VaultVolume {

    data class Entry(
        val fileId: String,
        val name: String,
        val originalSize: Long,
        val chunkCount: Int,
        val chunkSize: Int,
        val totalLen: Int,
        val numData: Int,
        val createdAt: Long
    )

    data class Index(val generation: Long, val entries: List<Entry>)

    data class ScrubReport(val filesChecked: Int, val chunksHealed: Int, val filesUnrecoverable: List<String>)

    // ---------- index ----------

    fun loadIndex(pool: List<File>, password: String): Index {
        var best: Index? = null
        var bestGen = -1L
        for (f in pool) {
            val bytes = readOrNull(f) ?: continue
            if (!FlacCarrierEngine.isFlac(bytes)) continue
            for (payload in extractOrEmpty(bytes)) {
                val blob = VaultCodec.decodeIndex(payload) ?: continue
                if (!blob.crcOk || blob.generation <= bestGen) continue
                val json = runCatching { CryptoEngine.decryptPayload(blob.encBody, password) }.getOrNull() ?: continue
                val parsed = runCatching { parseIndex(blob.generation, json) }.getOrNull() ?: continue
                best = parsed; bestGen = blob.generation
            }
        }
        return best ?: Index(0, emptyList())
    }

    fun list(pool: List<File>, password: String): List<Entry> =
        loadIndex(pool, password).entries.sortedByDescending { it.createdAt }

    private fun saveIndex(index: Index, pool: List<File>, password: String) {
        val json = indexToJson(index)
        val enc = CryptoEngine.encryptPayload(json.toByteArray(Charsets.UTF_8), password)
        val payload = VaultCodec.encodeIndex(index.generation, enc)

        // Drop only THIS passcode's stale index replicas (the ones that decrypt
        // with this password), then write fresh ones. Other passcodes' compartments
        // are left untouched, so one FLAC volume can hold several deniable vaults.
        for (f in pool) {
            val bytes = readOrNull(f) ?: continue
            if (!FlacCarrierEngine.isFlac(bytes)) continue
            val cleaned = FlacCarrierEngine.removeMatching(bytes) { payload ->
                val blob = VaultCodec.decodeIndex(payload)
                blob != null && blob.crcOk &&
                    runCatching { CryptoEngine.decryptPayload(blob.encBody, password) }.isSuccess
            }
            if (!cleaned.contentEquals(bytes)) writeAtomic(f, cleaned)
        }
        for (carrier in spreadCarriers(REPLICAS, pool)) {
            val bytes = readOrNull(carrier) ?: continue
            if (!FlacCarrierEngine.isFlac(bytes)) continue
            writeAtomic(carrier, FlacCarrierEngine.embed(bytes, payload))
        }
    }

    // ---------- vault / restore ----------

    fun vault(name: String, data: ByteArray, password: String, pool: List<File>, createdAt: Long): Entry {
        require(pool.isNotEmpty()) { "No FLAC carriers available to vault into." }
        val encrypted = CryptoEngine.encryptPayload(data, password)
        val raid = RaidVaultEngine.encodeRaidZ2WithHotSpares(encrypted, DATA_CHUNKS, true)
        val fileId = raid.fileId

        val carriers = assignChunkCarriers(raid.chunks, pool)
        raid.chunks.forEachIndexed { i, chunk ->
            val carrier = carriers[i]
            val bytes = readOrNull(carrier) ?: return@forEachIndexed
            if (!FlacCarrierEngine.isFlac(bytes)) return@forEachIndexed
            val payload = VaultCodec.encodeChunk(
                fileId, chunk.chunkIndex, raid.chunks.size,
                raid.chunkSize, raid.totalLength, DATA_CHUNKS, chunk.data
            )
            writeAtomic(carrier, FlacCarrierEngine.embed(bytes, payload))
        }

        val entry = Entry(
            fileId, name, data.size.toLong(), raid.chunks.size,
            raid.chunkSize, raid.totalLength, DATA_CHUNKS, createdAt
        )
        val current = loadIndex(pool, password)
        saveIndex(Index(current.generation + 1, current.entries + entry), pool, password)
        return entry
    }

    private data class Gathered(val chunks: Map<Int, ByteArray>, val badOrMissing: Boolean)

    private fun gatherChunks(fileId: String, expectedCount: Int, pool: List<File>): Gathered {
        val available = HashMap<Int, ByteArray>()
        var sawBad = false
        for (f in pool) {
            val bytes = readOrNull(f) ?: continue
            if (!FlacCarrierEngine.isFlac(bytes)) continue
            for (payload in extractOrEmpty(bytes)) {
                val c = VaultCodec.decodeChunk(payload) ?: continue
                if (c.fileId != fileId) continue
                if (!c.crcOk) { sawBad = true; continue } // corrupt -> let RAID rebuild
                available[c.index] = c.data
            }
        }
        val missing = available.size < expectedCount
        return Gathered(available, sawBad || missing)
    }

    fun restore(fileId: String, password: String, pool: List<File>): Pair<String, ByteArray> {
        val entry = loadIndex(pool, password).entries.firstOrNull { it.fileId == fileId }
            ?: throw IllegalStateException("Vaulted file not found in volume index.")
        val gathered = gatherChunks(fileId, entry.chunkCount, pool)
        val encrypted = RaidVaultEngine.reconstructRaidZ2(
            gathered.chunks, entry.totalLen, entry.chunkSize, entry.numData
        )
        val plain = CryptoEngine.decryptPayload(encrypted, password)
        return entry.name to plain
    }

    /** Verify every vaulted file; rebuild and re-embed any chunk that was bad or missing. */
    fun scrub(pool: List<File>, password: String): ScrubReport {
        val index = loadIndex(pool, password)
        var healed = 0
        val unrecoverable = ArrayList<String>()
        for (entry in index.entries) {
            val gathered = gatherChunks(entry.fileId, entry.chunkCount, pool)
            if (!gathered.badOrMissing) continue
            val rebuilt = runCatching {
                RaidVaultEngine.reconstructRaidZ2(gathered.chunks, entry.totalLen, entry.chunkSize, entry.numData)
            }.getOrNull()
            if (rebuilt == null) { unrecoverable.add(entry.name); continue }
            // Re-chunk and re-embed the healthy copy over the pool.
            val raid = RaidVaultEngine.encodeRaidZ2WithHotSpares(rebuilt, entry.numData, true)
            val carriers = assignChunkCarriers(raid.chunks, pool)
            raid.chunks.forEachIndexed { i, chunk ->
                val carrier = carriers[i]
                val bytes = readOrNull(carrier) ?: return@forEachIndexed
                // remove this file's stale chunk of the same index, then re-embed
                val cleaned = FlacCarrierEngine.removeMatching(bytes) { p ->
                    VaultCodec.decodeChunk(p)?.let { it.fileId == entry.fileId && it.index == chunk.chunkIndex } == true
                }
                val payload = VaultCodec.encodeChunk(
                    entry.fileId, chunk.chunkIndex, raid.chunks.size,
                    raid.chunkSize, raid.totalLength, entry.numData, chunk.data
                )
                writeAtomic(carrier, FlacCarrierEngine.embed(cleaned, payload))
            }
            healed++
        }
        return ScrubReport(index.entries.size, healed, unrecoverable)
    }

    fun delete(fileId: String, password: String, pool: List<File>) {
        for (f in pool) {
            val bytes = readOrNull(f) ?: continue
            if (!FlacCarrierEngine.isFlac(bytes)) continue
            val cleaned = FlacCarrierEngine.removeMatching(bytes) { p ->
                VaultCodec.decodeChunk(p)?.fileId == fileId
            }
            if (!cleaned.contentEquals(bytes)) writeAtomic(f, cleaned)
        }
        val current = loadIndex(pool, password)
        saveIndex(Index(current.generation + 1, current.entries.filterNot { it.fileId == fileId }), pool, password)
    }

    /** Duress wipe: strip every AlphaVault block (index and chunks) from all carriers. */
    fun wipeAll(pool: List<File>) {
        for (f in pool) {
            val bytes = readOrNull(f) ?: continue
            if (!FlacCarrierEngine.isFlac(bytes)) continue
            val cleaned = FlacCarrierEngine.removeAll(bytes)
            if (!cleaned.contentEquals(bytes)) writeAtomic(f, cleaned)
        }
    }

    fun usageBytes(pool: List<File>, password: String): Long {
        // Approximate on-carrier vault footprint: sum of embedded AVLT block sizes.
        var total = 0L
        for (f in pool) {
            val bytes = readOrNull(f) ?: continue
            if (!FlacCarrierEngine.isFlac(bytes)) continue
            for (p in extractOrEmpty(bytes)) total += p.size
        }
        return total
    }

    // ---------- carrier selection: spread across albums ----------

    /**
     * Place each RAID chunk on a carrier so that a chunk and its hot-spare mirror
     * live in different album folders. Then deleting or swapping one whole album
     * can never remove both copies of the same data, so the file still restores.
     *
     * Chunks are ordered data(0..d-1), P, Q, then mirrors of each of those. A
     * mirror's preferred folder is shifted by one from its primary's, so the two
     * never share an album when at least two albums exist.
     */
    private fun assignChunkCarriers(
        chunks: List<RaidVaultEngine.VaultChunkInfo>, pool: List<File>
    ): List<File> {
        if (pool.isEmpty()) return emptyList()
        val byFolder = LinkedHashMap<String, ArrayDeque<File>>()
        for (f in pool.sortedBy { it.absolutePath }) {
            byFolder.getOrPut(f.parentFile?.name ?: "") { ArrayDeque() }.add(f)
        }
        val folderNames = byFolder.keys.toList()
        val folderCount = folderNames.size.coerceAtLeast(1)
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
            val preferred = if (idx < primaryCount) {
                idx % folderCount
            } else {
                ((idx - primaryCount) % folderCount + 1) % folderCount
            }
            result[i] = pullPreferring(preferred)
        }
        val used = result.filterNotNull()
        val fallback = used.ifEmpty { pool }
        for (i in result.indices) if (result[i] == null) result[i] = fallback[i % fallback.size]
        return result.map { it!! }
    }

    /**
     * Choose [n] carriers, maximizing the number of distinct album folders used,
     * so that swapping or deleting one album removes as few chunks as possible.
     */
    private fun spreadCarriers(n: Int, pool: List<File>): List<File> {
        if (pool.isEmpty()) return emptyList()
        val byFolder = LinkedHashMap<String, ArrayDeque<File>>()
        for (f in pool.sortedBy { it.absolutePath }) {
            byFolder.getOrPut(f.parentFile?.name ?: "") { ArrayDeque() }.add(f)
        }
        val folders = byFolder.values.toMutableList()
        val chosen = ArrayList<File>(n)
        var fi = 0
        // Round-robin across folders so consecutive chunks land in different albums.
        while (chosen.size < n && folders.any { it.isNotEmpty() }) {
            val queue = folders[fi % folders.size]
            if (queue.isNotEmpty()) chosen.add(queue.removeFirst())
            fi++
        }
        // If we ran out of distinct carriers, cycle through what we used.
        if (chosen.isEmpty()) chosen.addAll(pool)
        while (chosen.size < n) chosen.add(chosen[chosen.size % chosen.size.coerceAtLeast(1)])
        return chosen
    }

    // ---------- json + io ----------

    private fun indexToJson(index: Index): String {
        val arr = JSONArray()
        for (e in index.entries) {
            arr.put(JSONObject().apply {
                put("fileId", e.fileId); put("name", e.name); put("originalSize", e.originalSize)
                put("chunkCount", e.chunkCount); put("chunkSize", e.chunkSize)
                put("totalLen", e.totalLen); put("numData", e.numData); put("createdAt", e.createdAt)
            })
        }
        return JSONObject().put("generation", index.generation).put("entries", arr).toString()
    }

    private fun parseIndex(generation: Long, json: ByteArray): Index {
        val obj = JSONObject(String(json, Charsets.UTF_8))
        val arr = obj.getJSONArray("entries")
        val entries = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            entries.add(
                Entry(
                    o.getString("fileId"), o.getString("name"), o.getLong("originalSize"),
                    o.getInt("chunkCount"), o.getInt("chunkSize"), o.getInt("totalLen"),
                    o.getInt("numData"), o.getLong("createdAt")
                )
            )
        }
        return Index(generation, entries)
    }

    private fun readOrNull(f: File): ByteArray? = runCatching { f.readBytes() }.getOrNull()

    private fun extractOrEmpty(bytes: ByteArray): List<ByteArray> =
        runCatching { FlacCarrierEngine.extractAll(bytes) }.getOrDefault(emptyList())

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, ".${target.name}.avtmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            // Fallback for filesystems where rename over an existing file fails.
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    companion object {
        const val DATA_CHUNKS = 4
        const val REPLICAS = 4
    }
}
