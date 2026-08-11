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
 * All carrier access is streaming and metadata-only: we never load a FLAC's
 * audio frames into memory, so vaulting works on memory-constrained DACs (it
 * just takes longer on large libraries). See [FlacCarrierEngine] for the
 * embed/extract mechanics.
 *
 * Data chunks (AVC1) and a replicated, generation-counted, encrypted index
 * (AVIX) both live in the carriers; there is no app-private database. Every
 * chunk is CRC-checksummed, so a corrupt or missing chunk is rebuilt from parity
 * or its mirror on restore.
 */
class VaultVolume {

    /** Progress sink: (completed, total, humanMessage). */
    fun interface Progress { fun update(done: Int, total: Int, message: String) }
    private val noProgress = Progress { _, _, _ -> }

    data class Entry(
        val fileId: String,
        val name: String,
        val originalSize: Long,
        val chunkCount: Int,
        val chunkSize: Int,
        val totalLen: Int,
        val numData: Int,
        val createdAt: Long,
        val colorLabel: Int = 0,        // 0 = none; otherwise an ARGB color the user assigned
        val tags: List<String> = emptyList(),
        val path: String = "/"          // folder this file lives in; "/" is the vault root
    )

    /**
     * The whole vault directory in one object. [folders] holds explicitly created
     * folders (so an empty folder persists); a file's own [Entry.path] also implies
     * its ancestor folders. Reorganizing the tree only rewrites this index, never
     * the data chunks, so a move is a metadata edit, like moving a file inside an
     * unlocked LUKS volume.
     */
    data class Index(
        val generation: Long,
        val entries: List<Entry>,
        val folders: List<String> = emptyList()
    )

    data class ScrubReport(val filesChecked: Int, val chunksHealed: Int, val filesUnrecoverable: List<String>)

    // ---------- index ----------

    fun loadIndex(pool: List<File>, password: String): Index {
        // Scan carriers in parallel (I/O bound). Each yields its best decryptable
        // index replica; the global winner is the highest generation.
        val candidates = parallelMap(pool) { f ->
            if (!FlacCarrierEngine.isFlacFile(f)) return@parallelMap null
            var best: Pair<Long, Index>? = null
            for (payload in extractOrEmpty(f)) {
                val blob = VaultCodec.decodeIndex(payload) ?: continue
                if (!blob.crcOk) continue
                if (best != null && blob.generation <= best!!.first) continue
                val json = runCatching { CryptoEngine.decryptPayload(blob.encBody, password) }.getOrNull() ?: continue
                val parsed = runCatching { parseIndex(blob.generation, json) }.getOrNull() ?: continue
                best = blob.generation to parsed
            }
            best
        }
        return candidates.filterNotNull().maxByOrNull { it.first }?.second ?: Index(0, emptyList())
    }

    /** Run [f] over the carriers concurrently and collect the results in order. */
    private fun <T> parallelMap(items: List<File>, f: (File) -> T): List<T> {
        if (items.size <= 1) return items.map(f)
        // Scale to the device's cores so a low-core DAC (or a constrained emulator)
        // is not swamped with threads it cannot service.
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val threads = minOf(cores, items.size, 8)
        val exec = java.util.concurrent.Executors.newFixedThreadPool(threads)
        return try {
            items.map { exec.submit(java.util.concurrent.Callable { f(it) }) }.map { it.get() }
        } finally {
            exec.shutdown()
        }
    }

    fun list(pool: List<File>, password: String): List<Entry> =
        loadIndex(pool, password).entries.sortedByDescending { it.createdAt }

    private fun saveIndex(index: Index, pool: List<File>, password: String, progress: Progress, base: Int, total: Int) {
        val json = indexToJson(index)
        val enc = CryptoEngine.encryptPayload(json.toByteArray(Charsets.UTF_8), password)
        val payload = VaultCodec.encodeIndex(index.generation, enc)

        // Drop only THIS passcode's stale index replicas, keeping other compartments.
        progress.update(base, total, "Updating vault index…")
        for (f in pool) {
            if (!FlacCarrierEngine.isFlacFile(f)) continue
            runCatching {
                FlacCarrierEngine.removeMatchingInFile(f) { p ->
                    val blob = VaultCodec.decodeIndex(p)
                    blob != null && blob.crcOk &&
                        runCatching { CryptoEngine.decryptPayload(blob.encBody, password) }.isSuccess
                }
            }
        }
        for (carrier in spreadCarriers(REPLICAS, pool)) {
            if (!FlacCarrierEngine.isFlacFile(carrier)) continue
            runCatching { FlacCarrierEngine.embedIntoFile(carrier, payload) }
        }
    }

    // ---------- vault / restore ----------

    @JvmOverloads
    fun vault(
        name: String, data: ByteArray, password: String, pool: List<File>,
        createdAt: Long, progress: Progress = noProgress
    ): Entry {
        require(pool.isNotEmpty()) { "No FLAC carriers available to vault into." }
        progress.update(0, 100, "Encrypting ${name}…")
        val encrypted = CryptoEngine.encryptPayload(data, password)
        progress.update(5, 100, "Splitting into RAID chunks…")
        val raid = RaidVaultEngine.encodeRaidZ2WithHotSpares(encrypted, DATA_CHUNKS, true)
        val fileId = raid.fileId

        val carriers = assignChunkCarriers(raid.chunks, pool)
        // Total steps ~ number of chunk embeds + an index-save step.
        val total = raid.chunks.size + 1
        raid.chunks.forEachIndexed { i, chunk ->
            val carrier = carriers[i]
            if (FlacCarrierEngine.isFlacFile(carrier)) {
                val payload = VaultCodec.encodeChunk(
                    fileId, chunk.chunkIndex, raid.chunks.size,
                    raid.chunkSize, raid.totalLength, DATA_CHUNKS, chunk.data
                )
                runCatching { FlacCarrierEngine.embedIntoFile(carrier, payload) }
            }
            progress.update(i + 1, total, "Hiding chunk ${i + 1} of ${raid.chunks.size} in ${carrier.name}…")
        }

        val entry = Entry(
            fileId, name, data.size.toLong(), raid.chunks.size,
            raid.chunkSize, raid.totalLength, DATA_CHUNKS, createdAt
        )
        val current = loadIndex(pool, password)
        saveIndex(
            current.copy(generation = current.generation + 1, entries = current.entries + entry),
            pool, password, progress, raid.chunks.size, total
        )
        progress.update(total, total, "Done.")
        return entry
    }

    /**
     * Persist an already-built index (bumping its generation is the caller's job).
     * Used by [VaultSession] to flush a batch of in-memory folder/move edits once,
     * on lock, instead of after every operation.
     */
    @JvmOverloads
    fun commitIndex(index: Index, pool: List<File>, password: String, progress: Progress = noProgress) =
        saveIndex(index, pool, password, progress, 0, 1)

    private fun gatherChunks(fileId: String, expectedCount: Int, pool: List<File>): Map<Int, ByteArray> {
        val perFile = parallelMap(pool) { f ->
            if (!FlacCarrierEngine.isFlacFile(f)) return@parallelMap emptyList<Pair<Int, ByteArray>>()
            extractOrEmpty(f).mapNotNull { payload ->
                val c = VaultCodec.decodeChunk(payload) ?: return@mapNotNull null
                if (c.fileId != fileId || !c.crcOk) null else c.index to c.data
            }
        }
        val available = HashMap<Int, ByteArray>()
        for (list in perFile) for ((idx, data) in list) available[idx] = data
        return available
    }

    @JvmOverloads
    fun restore(fileId: String, password: String, pool: List<File>, progress: Progress = noProgress): Pair<String, ByteArray> {
        val entry = loadIndex(pool, password).entries.firstOrNull { it.fileId == fileId }
            ?: throw IllegalStateException("Vaulted file not found in volume index.")
        progress.update(1, 3, "Gathering chunks from carriers…")
        val chunks = gatherChunks(fileId, entry.chunkCount, pool)
        progress.update(2, 3, "Reconstructing and decrypting…")
        val encrypted = RaidVaultEngine.reconstructRaidZ2(chunks, entry.totalLen, entry.chunkSize, entry.numData)
        val plain = CryptoEngine.decryptPayload(encrypted, password)
        progress.update(3, 3, "Done.")
        return entry.name to plain
    }

    fun scrub(pool: List<File>, password: String): ScrubReport {
        val index = loadIndex(pool, password)
        var healed = 0
        val unrecoverable = ArrayList<String>()
        for (entry in index.entries) {
            val chunks = gatherChunks(entry.fileId, entry.chunkCount, pool)
            if (chunks.size >= entry.chunkCount) continue
            val rebuilt = runCatching {
                RaidVaultEngine.reconstructRaidZ2(chunks, entry.totalLen, entry.chunkSize, entry.numData)
            }.getOrNull()
            if (rebuilt == null) { unrecoverable.add(entry.name); continue }
            val raid = RaidVaultEngine.encodeRaidZ2WithHotSpares(rebuilt, entry.numData, true)
            val carriers = assignChunkCarriers(raid.chunks, pool)
            raid.chunks.forEachIndexed { i, chunk ->
                val carrier = carriers[i]
                if (!FlacCarrierEngine.isFlacFile(carrier)) return@forEachIndexed
                runCatching {
                    FlacCarrierEngine.removeMatchingInFile(carrier) { p ->
                        VaultCodec.decodeChunk(p)?.let { it.fileId == entry.fileId && it.index == chunk.chunkIndex } == true
                    }
                    val payload = VaultCodec.encodeChunk(
                        entry.fileId, chunk.chunkIndex, raid.chunks.size,
                        raid.chunkSize, raid.totalLength, entry.numData, chunk.data
                    )
                    FlacCarrierEngine.embedIntoFile(carrier, payload)
                }
            }
            healed++
        }
        return ScrubReport(index.entries.size, healed, unrecoverable)
    }

    /** Rename a vaulted file in the index (data chunks are untouched). */
    fun rename(fileId: String, newName: String, password: String, pool: List<File>) {
        val current = loadIndex(pool, password)
        val updated = current.entries.map { if (it.fileId == fileId) it.copy(name = newName) else it }
        saveIndex(current.copy(generation = current.generation + 1, entries = updated), pool, password, noProgress, 0, 1)
    }

    /** Assign (or clear, with 0) a color label to a vaulted file. */
    fun setColor(fileId: String, color: Int, password: String, pool: List<File>) {
        val current = loadIndex(pool, password)
        val updated = current.entries.map { if (it.fileId == fileId) it.copy(colorLabel = color) else it }
        saveIndex(current.copy(generation = current.generation + 1, entries = updated), pool, password, noProgress, 0, 1)
    }

    /** Replace the tags on a vaulted file. */
    fun setTags(fileId: String, tags: List<String>, password: String, pool: List<File>) {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val current = loadIndex(pool, password)
        val updated = current.entries.map { if (it.fileId == fileId) it.copy(tags = clean) else it }
        saveIndex(current.copy(generation = current.generation + 1, entries = updated), pool, password, noProgress, 0, 1)
    }

    fun delete(fileId: String, password: String, pool: List<File>) {
        for (f in pool) {
            if (!FlacCarrierEngine.isFlacFile(f)) continue
            runCatching {
                FlacCarrierEngine.removeMatchingInFile(f) { p -> VaultCodec.decodeChunk(p)?.fileId == fileId }
            }
        }
        val current = loadIndex(pool, password)
        saveIndex(
            current.copy(generation = current.generation + 1, entries = current.entries.filterNot { it.fileId == fileId }),
            pool, password, noProgress, 0, 1
        )
    }

    /** Duress wipe: strip every AlphaVault block (index and chunks) from all carriers. */
    fun wipeAll(pool: List<File>) {
        for (f in pool) {
            if (!FlacCarrierEngine.isFlacFile(f)) continue
            runCatching { FlacCarrierEngine.removeMatchingInFile(f) { true } }
        }
    }

    fun usageBytes(pool: List<File>, password: String): Long =
        parallelMap(pool) { f ->
            if (!FlacCarrierEngine.isFlacFile(f)) 0L
            else extractOrEmpty(f).sumOf { it.size.toLong() }
        }.sum()

    // ---------- carrier selection ----------

    /**
     * Place each RAID chunk so a chunk and its hot-spare mirror land in different
     * album folders; deleting or swapping one album then can't remove both copies.
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
            val preferred = if (idx < primaryCount) idx % folderCount
            else ((idx - primaryCount) % folderCount + 1) % folderCount
            result[i] = pullPreferring(preferred)
        }
        val used = result.filterNotNull()
        val fallback = used.ifEmpty { pool }
        for (i in result.indices) if (result[i] == null) result[i] = fallback[i % fallback.size]
        return result.map { it!! }
    }

    private fun spreadCarriers(n: Int, pool: List<File>): List<File> {
        if (pool.isEmpty()) return emptyList()
        val byFolder = LinkedHashMap<String, ArrayDeque<File>>()
        for (f in pool.sortedBy { it.absolutePath }) {
            byFolder.getOrPut(f.parentFile?.name ?: "") { ArrayDeque() }.add(f)
        }
        val folders = byFolder.values.toMutableList()
        val chosen = ArrayList<File>(n)
        var fi = 0
        while (chosen.size < n && folders.any { it.isNotEmpty() }) {
            val queue = folders[fi % folders.size]
            if (queue.isNotEmpty()) chosen.add(queue.removeFirst())
            fi++
        }
        if (chosen.isEmpty()) chosen.addAll(pool.take(n))
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
                put("colorLabel", e.colorLabel)
                put("tags", JSONArray(e.tags))
                put("path", e.path)
            })
        }
        return JSONObject()
            .put("generation", index.generation)
            .put("entries", arr)
            .put("folders", JSONArray(index.folders))
            .toString()
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
                    o.getInt("numData"), o.getLong("createdAt"), o.optInt("colorLabel", 0),
                    o.optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                    VaultFs.normalize(o.optString("path", "/"))
                )
            )
        }
        val folders = obj.optJSONArray("folders")
            ?.let { arr -> (0 until arr.length()).map { VaultFs.normalize(arr.getString(it)) } }
            ?: emptyList()
        return Index(generation, entries, folders)
    }

    private fun extractOrEmpty(file: File): List<ByteArray> =
        runCatching { FlacCarrierEngine.extractAllFromFile(file) }.getOrDefault(emptyList())

    companion object {
        const val DATA_CHUNKS = 4
        const val REPLICAS = 4
    }
}
