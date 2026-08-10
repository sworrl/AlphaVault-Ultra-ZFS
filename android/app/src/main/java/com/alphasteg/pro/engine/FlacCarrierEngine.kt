package com.alphasteg.pro.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Hides arbitrary payloads inside real FLAC files without touching the audio.
 *
 * A FLAC file is "fLaC" followed by a chain of metadata blocks, then the audio
 * frames. Each metadata block has a 4-byte header: one flag/type byte (top bit =
 * "last metadata block", low 7 bits = type) and a 24-bit big-endian length.
 * We insert an APPLICATION block (type 2) carrying our data. Players ignore
 * APPLICATION blocks with an unknown id, and the audio frames are left exactly
 * as they were, so the track still decodes and plays bit-for-bit.
 *
 * This is container-level steganography, not audio-LSB: the payload is findable
 * by anyone who parses the metadata, but it is robust, reversible, and never
 * risks corrupting the music.
 */
object FlacCarrierEngine {

    private val MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
    private const val TYPE_APPLICATION = 2
    private const val MAX_BLOCK = 0xFFFFFF // 24-bit length ceiling

    /** The 4-byte FLAC APPLICATION id that marks our blocks. */
    val APP_ID = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())

    private data class Block(val headerPos: Int, val type: Int, val isLast: Boolean, val dataStart: Int, val length: Int)

    fun isFlac(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)

    fun isFlacFile(file: File): Boolean =
        runCatching {
            file.inputStream().use { ins ->
                val m = ByteArray(4)
                readFully(ins, m, 4) && m.contentEquals(MAGIC)
            }
        }.getOrDefault(false)

    // ---- streaming, file-based operations (never load the audio into memory) ----

    private class MetaRegion(val region: ByteArray, val blocks: List<Block>, val audioStart: Long)

    /** Read just the FLAC metadata region (everything before the audio frames). */
    private fun parseMetaFromFile(file: File): MetaRegion {
        file.inputStream().buffered().use { ins ->
            val magic = ByteArray(4)
            if (!readFully(ins, magic, 4) || !magic.contentEquals(MAGIC)) {
                throw IllegalArgumentException("Not a FLAC file: ${file.name}")
            }
            val region = ByteArrayOutputStream()
            region.write(magic)
            val blocks = ArrayList<Block>()
            var pos = 4
            while (true) {
                val hdr = ByteArray(4)
                if (!readFully(ins, hdr, 4)) throw IllegalArgumentException("Truncated FLAC metadata")
                val isLast = hdr[0].toInt() and 0x80 != 0
                val type = hdr[0].toInt() and 0x7F
                val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                    ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
                val data = ByteArray(len)
                if (!readFully(ins, data, len)) throw IllegalArgumentException("Truncated FLAC block")
                blocks.add(Block(pos, type, isLast, pos + 4, len))
                region.write(hdr); region.write(data)
                pos = pos + 4 + len
                if (isLast) break
            }
            return MetaRegion(region.toByteArray(), blocks, pos.toLong())
        }
    }

    /** Rewrite [file] as newMeta followed by the original audio frames, streamed. */
    private fun rewriteWithMeta(file: File, newMeta: ByteArray, audioStart: Long) {
        val tmp = File(file.parentFile, ".${file.name}.avtmp")
        file.inputStream().use { ins ->
            tmp.outputStream().buffered().use { out ->
                out.write(newMeta)
                var toSkip = audioStart
                while (toSkip > 0) {
                    val s = ins.skip(toSkip)
                    if (s <= 0) break
                    toSkip -= s
                }
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** Insert one APPLICATION block into a FLAC file, streaming the audio. */
    fun embedIntoFile(file: File, payload: ByteArray) {
        val blockData = APP_ID + payload
        require(blockData.size <= MAX_BLOCK) { "Payload too large for one FLAC metadata block." }
        val meta = parseMetaFromFile(file)
        val region = meta.region.copyOf()
        val last = meta.blocks.last()
        region[last.headerPos] = (region[last.headerPos].toInt() and 0x7F).toByte()
        val newHeader = byteArrayOf(
            (0x80 or TYPE_APPLICATION).toByte(),
            ((blockData.size ushr 16) and 0xFF).toByte(),
            ((blockData.size ushr 8) and 0xFF).toByte(),
            (blockData.size and 0xFF).toByte()
        )
        rewriteWithMeta(file, region + newHeader + blockData, meta.audioStart)
    }

    /** Our APPLICATION-block payloads in a FLAC file (reads metadata only). */
    fun extractAllFromFile(file: File): List<ByteArray> {
        val meta = parseMetaFromFile(file)
        val out = ArrayList<ByteArray>()
        for (b in meta.blocks) {
            if (b.type != TYPE_APPLICATION || b.length < 4) continue
            val id = meta.region.copyOfRange(b.dataStart, b.dataStart + 4)
            if (!id.contentEquals(APP_ID)) continue
            out.add(meta.region.copyOfRange(b.dataStart + 4, b.dataStart + b.length))
        }
        return out
    }

    /** Remove our matching APPLICATION blocks from a FLAC file. Returns true if changed. */
    fun removeMatchingInFile(file: File, shouldRemove: (payload: ByteArray) -> Boolean): Boolean {
        val meta = parseMetaFromFile(file)
        val kept = meta.blocks.filterNot { b ->
            if (b.type != TYPE_APPLICATION || b.length < 4) return@filterNot false
            val id = meta.region.copyOfRange(b.dataStart, b.dataStart + 4)
            if (!id.contentEquals(APP_ID)) return@filterNot false
            shouldRemove(meta.region.copyOfRange(b.dataStart + 4, b.dataStart + b.length))
        }
        if (kept.size == meta.blocks.size) return false
        val buf = ByteArrayOutputStream()
        buf.write(MAGIC)
        kept.forEachIndexed { i, b ->
            val isLast = i == kept.lastIndex
            buf.write((if (isLast) 0x80 else 0x00) or b.type)
            buf.write((b.length ushr 16) and 0xFF)
            buf.write((b.length ushr 8) and 0xFF)
            buf.write(b.length and 0xFF)
            buf.write(meta.region, b.dataStart, b.length)
        }
        rewriteWithMeta(file, buf.toByteArray(), meta.audioStart)
        return true
    }

    private fun readFully(ins: InputStream, buf: ByteArray, len: Int = buf.size): Boolean {
        var off = 0
        while (off < len) {
            val n = ins.read(buf, off, len - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun walk(bytes: ByteArray): Pair<List<Block>, Int> {
        require(isFlac(bytes)) { "Not a FLAC file (missing fLaC marker)." }
        val blocks = ArrayList<Block>()
        var pos = 4
        while (true) {
            if (pos + 4 > bytes.size) throw IllegalArgumentException("Truncated FLAC metadata.")
            val header = bytes[pos].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val type = header and 0x7F
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val dataStart = pos + 4
            blocks.add(Block(pos, type, isLast, dataStart, length))
            pos = dataStart + length
            if (isLast) break
        }
        return blocks to pos // pos == start of audio frames
    }

    /** Insert one APPLICATION block carrying [payload]. Returns the new file bytes. */
    fun embed(bytes: ByteArray, payload: ByteArray): ByteArray {
        val blockData = APP_ID + payload
        require(blockData.size <= MAX_BLOCK) { "Payload too large for one FLAC metadata block." }

        val (blocks, audioStart) = walk(bytes)
        val out = bytes.copyOf()

        // Clear the "last" flag on the current final metadata block; ours becomes last.
        val lastBlock = blocks.last()
        out[lastBlock.headerPos] = (out[lastBlock.headerPos].toInt() and 0x7F).toByte()

        val newHeader = byteArrayOf(
            (0x80 or TYPE_APPLICATION).toByte(),
            ((blockData.size ushr 16) and 0xFF).toByte(),
            ((blockData.size ushr 8) and 0xFF).toByte(),
            (blockData.size and 0xFF).toByte()
        )

        val buf = ByteArrayOutputStream(bytes.size + newHeader.size + blockData.size)
        buf.write(out, 0, audioStart)      // fLaC + metadata (last-flag cleared)
        buf.write(newHeader)               // our block header (isLast = 1)
        buf.write(blockData)               // AVLT id + payload
        buf.write(out, audioStart, out.size - audioStart) // untouched audio frames
        return buf.toByteArray()
    }

    /** Every payload from our APPLICATION blocks, in file order. */
    fun extractAll(bytes: ByteArray): List<ByteArray> {
        val (blocks, _) = walk(bytes)
        val out = ArrayList<ByteArray>()
        for (b in blocks) {
            if (b.type != TYPE_APPLICATION || b.length < 4) continue
            val id = bytes.copyOfRange(b.dataStart, b.dataStart + 4)
            if (!id.contentEquals(APP_ID)) continue
            out.add(bytes.copyOfRange(b.dataStart + 4, b.dataStart + b.length))
        }
        return out
    }

    /** Rewrite the file with all of our APPLICATION blocks removed (audio untouched). */
    fun removeAll(bytes: ByteArray): ByteArray = removeMatching(bytes) { true }

    /**
     * Rewrite the file, dropping our APPLICATION blocks whose payload (the bytes
     * after the AVLT id) satisfies [shouldRemove]. Audio frames are untouched.
     */
    fun removeMatching(bytes: ByteArray, shouldRemove: (payload: ByteArray) -> Boolean): ByteArray {
        val (blocks, audioStart) = walk(bytes)
        val kept = blocks.filterNot { b ->
            if (b.type != TYPE_APPLICATION || b.length < 4) return@filterNot false
            val id = bytes.copyOfRange(b.dataStart, b.dataStart + 4)
            if (!id.contentEquals(APP_ID)) return@filterNot false
            val payload = bytes.copyOfRange(b.dataStart + 4, b.dataStart + b.length)
            shouldRemove(payload)
        }
        if (kept.size == blocks.size) return bytes // nothing removed

        val buf = ByteArrayOutputStream(bytes.size)
        buf.write(bytes, 0, 4) // fLaC
        kept.forEachIndexed { i, b ->
            val isLast = i == kept.lastIndex
            val headerByte = (if (isLast) 0x80 else 0x00) or b.type
            buf.write(headerByte)
            buf.write((b.length ushr 16) and 0xFF)
            buf.write((b.length ushr 8) and 0xFF)
            buf.write(b.length and 0xFF)
            buf.write(bytes, b.dataStart, b.length)
        }
        buf.write(bytes, audioStart, bytes.size - audioStart)
        return buf.toByteArray()
    }
}
