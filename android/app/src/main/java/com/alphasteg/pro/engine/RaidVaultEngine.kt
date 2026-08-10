package com.alphasteg.pro.engine

import java.security.MessageDigest

/**
 * RAID-Z2 / RAID-6 engine with real dual parity.
 *
 * Parity is genuine Reed-Solomon over GF(2^8), the same math ZFS RAID-Z2 and
 * Linux md-raid6 use (H. P. Anvin, "The mathematics of RAID-6"):
 *   P = XOR of the data chunks
 *   Q = sum over i of g^i * D_i in GF(2^8)
 * From P and Q, ANY two lost data chunks are recovered by solving the 2x2 linear
 * system over the field. On top of that, chunks are mirrored to hot spares, so
 * losing whole albums is tolerated even beyond the two-parity guarantee.
 */
object RaidVaultEngine {

    enum class PoolMode { AUTO_WHOLE_LIBRARY, MANUAL_DISKS }

    data class VaultChunkInfo(
        val chunkIndex: Int,
        val isParity: Boolean,
        val isHotSpare: Boolean,
        val data: ByteArray
    )

    data class RaidZ2Result(
        val chunks: List<VaultChunkInfo>,
        val totalLength: Int,
        val chunkSize: Int,
        val fileId: String
    )

    fun encodeRaidZ2WithHotSpares(
        fileBytes: ByteArray,
        numDataChunks: Int = 4,
        enableHotSpares: Boolean = true
    ): RaidZ2Result {
        val totalLen = fileBytes.size
        val chunkSize = maxOf(1, (totalLen + numDataChunks - 1) / numDataChunks)
        val paddedLen = chunkSize * numDataChunks

        val padded = ByteArray(paddedLen)
        System.arraycopy(fileBytes, 0, padded, 0, totalLen)

        val dataChunks = Array(numDataChunks) { i ->
            padded.copyOfRange(i * chunkSize, (i + 1) * chunkSize)
        }

        val parityP = computeP(dataChunks, chunkSize)
        val parityQ = computeQ(dataChunks, chunkSize)

        val allChunks = ArrayList<VaultChunkInfo>()
        dataChunks.forEachIndexed { idx, bytes ->
            allChunks.add(VaultChunkInfo(idx, isParity = false, isHotSpare = false, data = bytes))
        }
        allChunks.add(VaultChunkInfo(numDataChunks, isParity = true, isHotSpare = false, data = parityP))
        allChunks.add(VaultChunkInfo(numDataChunks + 1, isParity = true, isHotSpare = false, data = parityQ))

        if (enableHotSpares) {
            val primaryCount = allChunks.size
            for (i in 0 until primaryCount) {
                val orig = allChunks[i]
                allChunks.add(
                    VaultChunkInfo(
                        chunkIndex = primaryCount + i,
                        isParity = orig.isParity,
                        isHotSpare = true,
                        data = orig.data.copyOf()
                    )
                )
            }
        }

        return RaidZ2Result(allChunks, totalLen, chunkSize, generateFileId("file_${System.currentTimeMillis()}"))
    }

    private fun computeP(data: Array<ByteArray>, chunkSize: Int): ByteArray {
        val p = ByteArray(chunkSize)
        for (chunk in data) for (i in 0 until chunkSize) p[i] = (p[i].toInt() xor chunk[i].toInt()).toByte()
        return p
    }

    private fun computeQ(data: Array<ByteArray>, chunkSize: Int): ByteArray {
        val q = ByteArray(chunkSize)
        val tmp = ByteArray(chunkSize)
        for (idx in data.indices) {
            GaloisField.mulInto(GaloisField.gExp(idx), data[idx], tmp)
            for (i in 0 until chunkSize) q[i] = (q[i].toInt() xor tmp[i].toInt()).toByte()
        }
        return q
    }

    /**
     * Reconstruct the original bytes from whatever chunks survive. A data chunk
     * is taken from its primary or its hot-spare mirror; up to two data chunks
     * missing from BOTH are rebuilt from the P and Q parity by real RS recovery.
     */
    fun reconstructRaidZ2(
        availableChunks: Map<Int, ByteArray>,
        totalLen: Int,
        chunkSize: Int,
        numDataChunks: Int = 4
    ): ByteArray {
        val mirrorOffset = numDataChunks + 2
        fun get(idx: Int): ByteArray? = availableChunks[idx] ?: availableChunks[idx + mirrorOffset]

        val data = arrayOfNulls<ByteArray>(numDataChunks)
        for (i in 0 until numDataChunks) data[i] = get(i)
        val pParity = get(numDataChunks)
        val qParity = get(numDataChunks + 1)

        val missing = (0 until numDataChunks).filter { data[it] == null }

        when (missing.size) {
            0 -> { /* all present */ }
            1 -> {
                val x = missing[0]
                data[x] = when {
                    pParity != null -> recoverOneFromP(data, pParity, x, chunkSize)
                    qParity != null -> recoverOneFromQ(data, qParity, x, chunkSize)
                    else -> throw IllegalStateException("Chunk $x lost and no parity available.")
                }
            }
            2 -> {
                if (pParity == null || qParity == null) {
                    throw IllegalStateException("Two chunks lost but both P and Q parity are not available.")
                }
                recoverTwo(data, pParity, qParity, missing[0], missing[1], chunkSize)
            }
            else -> throw IllegalStateException(
                "Cannot recover: ${missing.size} data chunks missing (max 2 with dual parity + mirrors)."
            )
        }

        val assembled = ByteArray(chunkSize * numDataChunks)
        for (i in 0 until numDataChunks) System.arraycopy(data[i]!!, 0, assembled, i * chunkSize, chunkSize)
        return assembled.copyOf(totalLen)
    }

    private fun recoverOneFromP(data: Array<ByteArray?>, p: ByteArray, x: Int, chunkSize: Int): ByteArray {
        val out = ByteArray(chunkSize)
        for (i in 0 until chunkSize) {
            var v = p[i].toInt() and 0xFF
            for (j in data.indices) if (j != x) v = v xor (data[j]!![i].toInt() and 0xFF)
            out[i] = v.toByte()
        }
        return out
    }

    private fun recoverOneFromQ(data: Array<ByteArray?>, q: ByteArray, x: Int, chunkSize: Int): ByteArray {
        // D_x = (Q ^ sum_{i!=x} g^i D_i) * g^{-x}
        val invGx = GaloisField.inv(GaloisField.gExp(x))
        val out = ByteArray(chunkSize)
        for (i in 0 until chunkSize) {
            var acc = q[i].toInt() and 0xFF
            for (j in data.indices) if (j != x) acc = acc xor GaloisField.mul(GaloisField.gExp(j), data[j]!![i].toInt() and 0xFF)
            out[i] = GaloisField.mul(acc, invGx).toByte()
        }
        return out
    }

    private fun recoverTwo(
        data: Array<ByteArray?>, p: ByteArray, q: ByteArray, x: Int, y: Int, chunkSize: Int
    ) {
        // Pd = D_x ^ D_y ; Qd = g^x D_x ^ g^y D_y  (from stored P/Q minus survivors)
        // => D_x = (Qd ^ g^y * Pd) / (g^x ^ g^y) ; D_y = Pd ^ D_x
        val gx = GaloisField.gExp(x)
        val gy = GaloisField.gExp(y)
        val denomInv = GaloisField.inv(gx xor gy)
        val dx = ByteArray(chunkSize)
        val dy = ByteArray(chunkSize)
        for (i in 0 until chunkSize) {
            var pd = p[i].toInt() and 0xFF
            var qd = q[i].toInt() and 0xFF
            for (j in data.indices) if (j != x && j != y) {
                val dj = data[j]!![i].toInt() and 0xFF
                pd = pd xor dj
                qd = qd xor GaloisField.mul(GaloisField.gExp(j), dj)
            }
            val vx = GaloisField.mul(qd xor GaloisField.mul(gy, pd), denomInv)
            dx[i] = vx.toByte()
            dy[i] = (pd xor vx).toByte()
        }
        data[x] = dx
        data[y] = dy
    }

    fun generateFileId(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest("$seed:${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }
}
