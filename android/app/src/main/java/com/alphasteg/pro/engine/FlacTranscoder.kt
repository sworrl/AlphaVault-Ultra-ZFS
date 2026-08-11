package com.alphasteg.pro.engine

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FLAC <-> PCM using the platform codecs, so the LSB carrier ([LsbStego]) can read
 * a carrier's samples, have its bits changed, and be written back as a valid,
 * lossless FLAC.
 *
 * FLAC is lossless, so the exact 16-bit samples handed to [encode] come back
 * bit-for-bit from [decode] on the next read; that is what lets keyed LSB data
 * survive a re-encode. This runs on the phone (not the DAC), which does the work.
 *
 * The encoder emits raw FLAC frames plus a STREAMINFO in csd-0; MediaMuxer has no
 * FLAC container, so [encode] writes the `fLaC` stream marker, the STREAMINFO
 * metadata block, then the frames itself.
 */
object FlacTranscoder {

    data class Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    private const val TIMEOUT_US = 10_000L

    /** Decode a FLAC file to interleaved 16-bit PCM. */
    fun decode(input: File): Pcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i; format = f; break
            }
        }
        require(track >= 0 && format != null) { "No audio track in ${input.name}" }
        extractor.selectTrack(track)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<Short>(1 shl 20)
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIdx >= 0) {
                val buf = codec.getOutputBuffer(outIdx)!!
                val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (shorts.hasRemaining()) out.add(shorts.get())
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }
        codec.stop(); codec.release(); extractor.release()
        return Pcm(ShortArray(out.size) { out[it] }, sampleRate, channels)
    }

    /** Encode interleaved 16-bit PCM to a FLAC file. */
    fun encode(pcm: Pcm, output: OutputStream) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, pcm.sampleRate, pcm.channels)
        format.setInteger(MediaFormat.KEY_BIT_RATE, pcm.sampleRate * pcm.channels * 16)
        format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error("No FLAC encoder on this device")
        val codec = MediaCodec.createByCodecName(encoderName)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        output.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))
        var wroteStreamInfo = false

        val info = MediaCodec.BufferInfo()
        var inPos = 0
        var sawInputEos = false
        var sawOutputEos = false
        val bytesTotal = pcm.samples.size * 2
        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    buf.clear()
                    val cap = buf.capacity()
                    val remaining = bytesTotal - inPos
                    val chunk = minOf(cap, remaining)
                    if (chunk > 0) {
                        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val count = chunk / 2
                        sb.put(pcm.samples, inPos / 2, count)
                        codec.queueInputBuffer(inIdx, 0, count * 2, 0, 0)
                        inPos += count * 2
                    }
                    if (inPos >= bytesTotal) {
                        val e = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (e >= 0) { codec.queueInputBuffer(e, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); sawInputEos = true }
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIdx >= 0) {
                val buf = codec.getOutputBuffer(outIdx)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // csd-0 is the 34-byte STREAMINFO body; wrap it as the last metadata block.
                    writeStreamInfoBlock(output, buf, info.size)
                    wroteStreamInfo = true
                } else if (info.size > 0) {
                    val arr = ByteArray(info.size)
                    buf.position(info.offset); buf.get(arr)
                    output.write(arr)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }
        require(wroteStreamInfo) { "FLAC encoder produced no STREAMINFO" }
        codec.stop(); codec.release(); output.flush()
    }

    private fun writeStreamInfoBlock(out: OutputStream, buf: ByteBuffer, size: Int) {
        val body = ByteArray(size)
        buf.position(0); buf.get(body)
        // Metadata block header: last-block flag (0x80) | type 0 (STREAMINFO), then 24-bit length.
        val len = body.size
        out.write(0x80)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(body)
    }
}
