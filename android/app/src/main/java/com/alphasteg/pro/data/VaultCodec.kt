package com.alphasteg.pro.data

import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Binary framing for what we embed in FLAC carriers.
 *
 * Two payload kinds, both self-describing and CRC32-checksummed (btrfs-style):
 *  - a data chunk (AVC1): one RAID-Z2 chunk of one vaulted file.
 *  - a volume index (AVIX): the encrypted table of contents for the whole
 *    vault, carrying a generation number so the newest replica wins.
 *
 * Every payload verifies its own checksum on decode, so a corrupted block reads
 * back as "not OK" and the RAID layer rebuilds it from parity or a mirror.
 */
object VaultCodec {

    val CHUNK_MAGIC = "AVC1".toByteArray(Charsets.US_ASCII)
    val INDEX_MAGIC = "AVIX".toByteArray(Charsets.US_ASCII)
    const val FILE_ID_LEN = 16

    private fun crc(data: ByteArray): Int = CRC32().apply { update(data) }.value.toInt()

    // ---- data chunk ----

    data class Chunk(
        val fileId: String,
        val index: Int,
        val count: Int,
        val chunkSize: Int,
        val totalLen: Int,
        val numData: Int,
        val data: ByteArray,
        val crcOk: Boolean
    )

    private const val CHUNK_HEADER = 4 + FILE_ID_LEN + 2 + 2 + 4 + 4 + 1 + 4

    fun encodeChunk(
        fileId: String, index: Int, count: Int,
        chunkSize: Int, totalLen: Int, numData: Int, data: ByteArray
    ): ByteArray {
        val id = fileId.toByteArray(Charsets.US_ASCII)
        require(id.size == FILE_ID_LEN) { "fileId must be $FILE_ID_LEN bytes" }
        return ByteBuffer.allocate(CHUNK_HEADER + data.size).apply {
            put(CHUNK_MAGIC); put(id)
            putShort(index.toShort()); putShort(count.toShort())
            putInt(chunkSize); putInt(totalLen); put(numData.toByte())
            putInt(crc(data)); put(data)
        }.array()
    }

    fun decodeChunk(payload: ByteArray): Chunk? {
        if (payload.size < CHUNK_HEADER) return null
        val buf = ByteBuffer.wrap(payload)
        val magic = ByteArray(4).also { buf.get(it) }
        if (!magic.contentEquals(CHUNK_MAGIC)) return null
        val id = ByteArray(FILE_ID_LEN).also { buf.get(it) }
        val index = buf.short.toInt() and 0xFFFF
        val count = buf.short.toInt() and 0xFFFF
        val chunkSize = buf.int
        val totalLen = buf.int
        val numData = buf.get().toInt() and 0xFF
        val expectedCrc = buf.int
        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        return Chunk(
            String(id, Charsets.US_ASCII), index, count, chunkSize, totalLen, numData,
            data, crc(data) == expectedCrc
        )
    }

    // ---- volume index ----

    data class IndexBlob(val generation: Long, val encBody: ByteArray, val crcOk: Boolean)

    private const val INDEX_HEADER = 4 + 8 + 4 + 4

    fun encodeIndex(generation: Long, encBody: ByteArray): ByteArray =
        ByteBuffer.allocate(INDEX_HEADER + encBody.size).apply {
            put(INDEX_MAGIC); putLong(generation); putInt(crc(encBody)); putInt(encBody.size); put(encBody)
        }.array()

    fun decodeIndex(payload: ByteArray): IndexBlob? {
        if (payload.size < INDEX_HEADER) return null
        val buf = ByteBuffer.wrap(payload)
        val magic = ByteArray(4).also { buf.get(it) }
        if (!magic.contentEquals(INDEX_MAGIC)) return null
        val gen = buf.long
        val expectedCrc = buf.int
        val len = buf.int
        if (len < 0 || len > buf.remaining()) return null
        val body = ByteArray(len).also { buf.get(it) }
        return IndexBlob(gen, body, crc(body) == expectedCrc)
    }

    fun isIndexPayload(payload: ByteArray): Boolean =
        payload.size >= 4 && payload.copyOfRange(0, 4).contentEquals(INDEX_MAGIC)

    fun isChunkPayload(payload: ByteArray): Boolean =
        payload.size >= 4 && payload.copyOfRange(0, 4).contentEquals(CHUNK_MAGIC)
}
