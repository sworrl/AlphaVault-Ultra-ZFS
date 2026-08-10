package com.alphasteg.pro.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

object LsbStegoEngine {

    private val LSB_MAGIC = byteArrayOf(0xAF.toByte(), 0x55.toByte())

    fun embedLsb(pcmSamples: ShortArray, payload: ByteArray, password: String?): ShortArray {
        val encryptedPayload = CryptoEngine.encryptPayload(payload, password)
        val fileLen = encryptedPayload.size

        // Build Header: MAGIC (2) + LEN (4) + PAYLOAD
        val header = ByteBuffer.allocate(6 + fileLen).order(ByteOrder.LITTLE_ENDIAN)
        header.put(LSB_MAGIC)
        header.putInt(fileLen)
        header.put(encryptedPayload)
        val fullData = header.array()

        val bits = IntArray(fullData.size * 8)
        for (i in fullData.indices) {
            val b = fullData[i].toInt() and 0xFF
            for (j in 0 until 8) {
                bits[i * 8 + j] = (b shr (7 - j)) and 1
            }
        }

        if (pcmSamples.size < bits.size) {
            throw IllegalArgumentException("Audio track is too short to store payload.")
        }

        val outputSamples = pcmSamples.copyOf()
        for (i in bits.indices) {
            var sample = outputSamples[i].toInt() and 0xFFFF
            sample = (sample and 0xFFFE) or bits[i]
            outputSamples[i] = if (sample < 32768) sample.toShort() else (sample - 65536).toShort()
        }

        return outputSamples
    }

    fun extractLsb(pcmSamples: ShortArray, password: String?): ByteArray {
        if (pcmSamples.size < 48) {
            throw IllegalArgumentException("Audio track too short.")
        }

        // Extract 48 bits for Header
        val headerBytes = ByteArray(6)
        for (bIdx in 0 until 6) {
            var valByte = 0
            for (bitIdx in 0 until 8) {
                val bit = pcmSamples[bIdx * 8 + bitIdx].toInt() and 1
                valByte = (valByte shl 1) or bit
            }
            headerBytes[bIdx] = valByte.toByte()
        }

        if (headerBytes[0] != LSB_MAGIC[0] || headerBytes[1] != LSB_MAGIC[1]) {
            throw IllegalArgumentException("No AlphaSteg LSB signature found in this track.")
        }

        val fileLen = ByteBuffer.wrap(headerBytes, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val totalBitsNeeded = 48 + fileLen * 8

        if (pcmSamples.size < totalBitsNeeded) {
            throw IllegalArgumentException("Corrupted LSB payload or truncated carrier file.")
        }

        val rawPayload = ByteArray(fileLen)
        for (bIdx in 0 until fileLen) {
            var valByte = 0
            for (bitIdx in 0 until 8) {
                val bit = pcmSamples[48 + bIdx * 8 + bitIdx].toInt() and 1
                valByte = (valByte shl 1) or bit
            }
            rawPayload[bIdx] = valByte.toByte()
        }

        return CryptoEngine.decryptPayload(rawPayload, password)
    }
}
