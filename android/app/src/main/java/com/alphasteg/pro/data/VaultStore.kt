package com.alphasteg.pro.data

import android.content.Context
import com.alphasteg.pro.engine.CryptoEngine
import com.alphasteg.pro.engine.RaidVaultEngine
import org.json.JSONObject
import java.io.File

/**
 * Durable store for vaulted files.
 *
 * A vaulted file is: plaintext -> 768-bit cascade encryption -> RAID-Z2 chunking
 * (data + P/Q parity + hot-spare mirror). Every chunk is written to app-private
 * storage under vault/<fileId>/, alongside a meta.json describing how to put it
 * back together. Restore reverses the pipeline: read chunks -> RAID reconstruct
 * -> cascade decrypt -> original bytes.
 *
 * Chunks live in app-private external storage, so they survive restarts and are
 * not indexed by MediaStore. Embedding chunks inside the audio of real FLAC
 * carriers is a separate, later step; this store is the persistence layer it
 * will build on.
 */
class VaultStore(context: Context) {

    private val baseDir: File = File(context.getExternalFilesDir(null), "vault").apply { mkdirs() }

    data class VaultedFile(
        val fileId: String,
        val name: String,
        val originalSize: Long,
        val chunkCount: Int,
        val createdAt: Long
    )

    fun vault(name: String, data: ByteArray, password: String): VaultedFile {
        val encrypted = CryptoEngine.encryptPayload(data, password)
        val raid = RaidVaultEngine.encodeRaidZ2WithHotSpares(
            fileBytes = encrypted,
            numDataChunks = DATA_CHUNKS,
            enableHotSpares = true
        )

        val dir = File(baseDir, raid.fileId).apply { mkdirs() }
        for (chunk in raid.chunks) {
            File(dir, "chunk_${chunk.chunkIndex}.bin").writeBytes(chunk.data)
        }

        val createdAt = System.currentTimeMillis()
        val meta = JSONObject().apply {
            put("name", name)
            put("originalSize", data.size)
            put("chunkSize", raid.chunkSize)
            put("totalLen", raid.totalLength)
            put("numDataChunks", DATA_CHUNKS)
            put("chunkCount", raid.chunks.size)
            put("createdAt", createdAt)
        }
        File(dir, META).writeText(meta.toString())

        return VaultedFile(raid.fileId, name, data.size.toLong(), raid.chunks.size, createdAt)
    }

    fun list(): List<VaultedFile> {
        val dirs = baseDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val metaFile = File(dir, META)
            if (!metaFile.exists()) return@mapNotNull null
            runCatching {
                val m = JSONObject(metaFile.readText())
                VaultedFile(
                    fileId = dir.name,
                    name = m.getString("name"),
                    originalSize = m.getLong("originalSize"),
                    chunkCount = m.optInt("chunkCount", 0),
                    createdAt = m.optLong("createdAt", 0L)
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    /** Reconstruct and decrypt a vaulted file. Returns (originalName, plaintext). */
    fun restore(fileId: String, password: String): Pair<String, ByteArray> {
        val dir = File(baseDir, fileId)
        val meta = JSONObject(File(dir, META).readText())
        val chunkSize = meta.getInt("chunkSize")
        val totalLen = meta.getInt("totalLen")
        val numDataChunks = meta.getInt("numDataChunks")

        val available = HashMap<Int, ByteArray>()
        val rx = Regex("""chunk_(\d+)\.bin""")
        dir.listFiles()?.forEach { f ->
            rx.matchEntire(f.name)?.let { m ->
                available[m.groupValues[1].toInt()] = f.readBytes()
            }
        }

        val encrypted = RaidVaultEngine.reconstructRaidZ2(available, totalLen, chunkSize, numDataChunks)
        val plaintext = CryptoEngine.decryptPayload(encrypted, password)
        return meta.getString("name") to plaintext
    }

    fun delete(fileId: String): Boolean =
        File(baseDir, fileId).deleteRecursively()

    /** Wipe every vaulted file. Used by the duress path. */
    fun wipeAll(): Boolean {
        val ok = baseDir.deleteRecursively()
        baseDir.mkdirs()
        return ok
    }

    data class Usage(val fileCount: Int, val originalBytes: Long, val storedBytes: Long)

    /** Totals across the vault: file count, sum of original sizes, on-disk chunk bytes. */
    fun usage(): Usage {
        val dirs = baseDir.listFiles { f -> f.isDirectory } ?: return Usage(0, 0, 0)
        var count = 0
        var original = 0L
        var stored = 0L
        for (dir in dirs) {
            val metaFile = File(dir, META)
            if (!metaFile.exists()) continue
            count++
            runCatching { JSONObject(metaFile.readText()).getLong("originalSize") }
                .getOrNull()?.let { original += it }
            dir.listFiles()?.forEach { f -> if (f.isFile) stored += f.length() }
        }
        return Usage(count, original, stored)
    }

    companion object {
        private const val META = "meta.json"
        private const val DATA_CHUNKS = 4
    }
}
