package com.alphasteg.pro.engine

/**
 * Random-access bytes that need not be contiguous in memory.
 *
 * A restore gathers RAID chunks and then, in the common case where nothing was
 * lost, the payload is simply those chunks in order. Joining them into one array
 * would double peak memory for no benefit, so the crypto layer reads through this
 * instead and the chunks stay where they are.
 */
interface ByteSource {

    val size: Long

    /** Copy [len] bytes starting at [pos] into [dst] at [off]. */
    fun copyInto(pos: Long, dst: ByteArray, off: Int, len: Int)

    /** Read a big-endian int at [pos]. */
    fun readInt(pos: Long): Int {
        val b = ByteArray(4)
        copyInto(pos, b, 0, 4)
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    fun slice(pos: Long, len: Int): ByteArray =
        ByteArray(len).also { copyInto(pos, it, 0, len) }
}

/** A plain array, for callers that already hold the whole payload. */
class ArraySource(private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()

    override fun copyInto(pos: Long, dst: ByteArray, off: Int, len: Int) {
        require(pos >= 0 && pos + len <= data.size) { "read out of range" }
        System.arraycopy(data, pos.toInt(), dst, off, len)
    }
}

/**
 * Equal-sized chunks read as one contiguous run of [size] bytes.
 *
 * Every RAID chunk is padded to the same length, so locating a byte is division
 * rather than a search. The final chunk is usually partly padding, which is why
 * the logical [size] is given separately and reads past it are refused.
 */
class StripedSource(
    private val chunks: List<ByteArray>,
    private val chunkSize: Int,
    override val size: Long
) : ByteSource {

    init {
        require(chunkSize > 0) { "chunk size must be positive" }
        require(size <= chunks.size.toLong() * chunkSize) { "chunks do not cover $size bytes" }
    }

    override fun copyInto(pos: Long, dst: ByteArray, off: Int, len: Int) {
        require(pos >= 0 && pos + len <= size) { "read out of range" }
        var remaining = len
        var from = pos
        var to = off
        while (remaining > 0) {
            val chunk = (from / chunkSize).toInt()
            val within = (from % chunkSize).toInt()
            val take = minOf(remaining, chunkSize - within)
            System.arraycopy(chunks[chunk], within, dst, to, take)
            from += take
            to += take
            remaining -= take
        }
    }
}
