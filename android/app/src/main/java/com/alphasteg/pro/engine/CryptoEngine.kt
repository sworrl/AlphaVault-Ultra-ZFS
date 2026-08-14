package com.alphasteg.pro.engine

import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AlphaVault cascade crypto: 768 bits of combined key material across two
 * authenticated ciphers plus an outer HMAC, applied to independently sealed
 * frames so a large file never has to exist in memory several times over.
 *
 * - Password stretch: PBKDF2-HMAC-SHA512, 500,000 iterations -> 512-bit master key
 * - Per-frame subkeys: HKDF-Expand-SHA512 over the master key
 * - Layer 1: AES-256-GCM (128-bit tag)
 * - Layer 2: ChaCha20-Poly1305 (256-bit stream cipher, 128-bit authenticator)
 * - Outer HMAC-SHA512 over the whole envelope, verified before any decryption
 *
 * ## Why the two-stage derivation
 *
 * The stretch is deliberately expensive — half a million SHA-512 rounds — which
 * is correct for resisting a password guess but ruinous if it runs per payload.
 * It measured at ~13.5 s per call on a HiBy M500, and [VaultVolume.loadIndex]
 * calls it once per candidate carrier, so browsing a vault cost the better part
 * of a minute on that hardware.
 *
 * So the stretch happens once per (password, salt) and is cached for the process;
 * every frame takes its own AES, ChaCha and HMAC keys from that master by HKDF,
 * which costs microseconds. Guess-resistance is unchanged: an attacker still pays
 * the full PBKDF2 cost per password candidate.
 *
 * ## Why frames
 *
 * An AEAD cipher cannot emit plaintext until it has verified its tag, so Java's
 * GCM and Poly1305 implementations buffer the *entire* message internally. Sealing
 * one big blob therefore forced several full-size copies to exist at once —
 * measured as a ~64 MB practical ceiling on a device with a 256 MB heap. Splitting
 * the payload into [FRAME_SIZE] segments, each sealed on its own, means decryption
 * streams: peak cost is one frame, not one file.
 *
 * Frames cannot be reordered, duplicated or dropped: each frame's subkeys are
 * bound to its index via HKDF, the declared payload length is authenticated in the
 * header, and the outer HMAC covers every byte of the envelope.
 *
 * Encryption reuses one [sessionSalt] for the life of the process so writes hit
 * the key cache too. Salt reuse under a single password is sound here: the salt
 * exists to stop precomputation across vaults, not to make payloads unique, and
 * uniqueness comes from fresh per-frame nonces.
 *
 * Keys live in memory only. Call [clearKeyCache] when the vault locks.
 */
object CryptoEngine {

    private const val PBKDF2_ITERATIONS = 500_000
    private const val SALT_SIZE = 32
    private const val GCM_NONCE_SIZE = 12
    private const val CHACHA_NONCE_SIZE = 12
    private const val TAG_SIZE_BITS = 128
    private const val MASTER_KEY_BITS = 512
    private const val SUBKEY_BYTES = 96      // 32 AES + 32 ChaCha + 32 HMAC
    private const val HMAC_SIZE = 64

    /** Plaintext bytes per frame. Peak decryption memory is about this much. */
    const val FRAME_SIZE = 1 shl 20          // 1 MiB

    /** MAGIC(8) + SALT(32) + FRAME_SIZE(4) + TOTAL_LEN(8). */
    private const val HEADER_SIZE = 8 + SALT_SIZE + 4 + 8

    /** Per-frame preamble: both nonces plus the ciphertext length. */
    private const val FRAME_HEADER = GCM_NONCE_SIZE + CHACHA_NONCE_SIZE + 4

    /** Envelope marker. Bumped from AVMAX769 when the payload became framed. */
    private val CASCADE_MAGIC = "AVMAX770".toByteArray(Charsets.UTF_8)

    /** Domain separation for HKDF, so this schedule cannot collide with another. */
    private val HKDF_INFO = "AlphaVault-cascade-v3".toByteArray(Charsets.UTF_8)
    private val HKDF_OUTER = "AlphaVault-outer-mac".toByteArray(Charsets.UTF_8)

    private val random = SecureRandom()

    /**
     * One salt per process, so every write in a session shares a master key and
     * pays the stretch once. Regenerated on next launch.
     */
    private val sessionSalt: ByteArray by lazy { ByteArray(SALT_SIZE).also { random.nextBytes(it) } }

    private const val MAX_CACHED_KEYS = 32
    private val keyCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            if (size <= MAX_CACHED_KEYS) return false
            eldest?.value?.fill(0)
            return true
        }
    }

    /**
     * Drop every cached master key, zeroing the bytes. Call on lock: without this
     * an unlocked-then-locked vault would leave usable key material in the heap.
     */
    fun clearKeyCache() {
        synchronized(keyCache) {
            for (v in keyCache.values) v.fill(0)
            keyCache.clear()
        }
    }

    // ---- key schedule ----

    /** Cache identity for a password and salt that never stores the password itself. */
    private fun cacheId(password: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(password.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(salt)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** PBKDF2 stretch, memoized. The only expensive step in the engine. */
    private fun masterKey(password: String, salt: ByteArray): ByteArray {
        val id = cacheId(password, salt)
        synchronized(keyCache) { keyCache[id] }?.let { return it }

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, MASTER_KEY_BITS)
        val derived = factory.generateSecret(spec).encoded

        synchronized(keyCache) { keyCache[id] = derived }
        return derived
    }

    /** HKDF-Expand (RFC 5869) over SHA-512. */
    private fun expand(master: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(master, "HmacSHA512"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, take)
            offset += take
            counter++
        }
        return out
    }

    /** The cipher keys for one frame, bound to its index and nonces. */
    private class Subkeys(material: ByteArray) {
        val aes: ByteArray = material.copyOfRange(0, 32)
        val chacha: ByteArray = material.copyOfRange(32, 64)
    }

    private fun frameKeys(
        master: ByteArray,
        frameIndex: Int,
        aesNonce: ByteArray,
        chachaNonce: ByteArray
    ): Subkeys {
        val info = HKDF_INFO + intBytes(frameIndex) + aesNonce + chachaNonce
        return Subkeys(expand(master, info, SUBKEY_BYTES))
    }

    /** Key for the envelope-wide HMAC, independent of any frame. */
    private fun outerMacKey(master: ByteArray): ByteArray = expand(master, HKDF_OUTER, 32)

    // Android's Conscrypt names this cipher "ChaCha20/Poly1305/NoPadding"; the
    // desktop JDK's SunJCE names it "ChaCha20-Poly1305". Try both so the engine
    // runs unchanged on device and under host-JVM unit tests.
    private fun chaChaPoly1305Cipher(): Cipher =
        try {
            Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        } catch (e: java.security.NoSuchAlgorithmException) {
            Cipher.getInstance("ChaCha20-Poly1305")
        }

    // ---- encryption ----

    /** Bytes each AEAD layer appends. Two layers, so 32 per frame. */
    private const val AEAD_TAG = 16

    /** Frames a payload of [size] takes; an empty payload still gets one. */
    private fun frameCount(size: Int): Int =
        if (size <= 0) 1 else (size + FRAME_SIZE - 1) / FRAME_SIZE

    /** Exact envelope size, so encryption can allocate once and never grow. */
    fun envelopeSize(plaintextSize: Int): Int {
        val frames = frameCount(plaintextSize)
        return HEADER_SIZE + frames * (FRAME_HEADER + 2 * AEAD_TAG) + plaintextSize + HMAC_SIZE
    }

    fun encryptPayload(data: ByteArray, password: String?): ByteArray {
        if (password.isNullOrBlank()) return data

        val salt = sessionSalt
        val master = masterKey(password.trim(), salt)

        // One exactly-sized buffer, written in place. A growing stream plus the
        // usual array concatenations would have held three copies of a large file
        // at once, which is precisely what breaks on a small-heap device.
        val out = ByteArray(envelopeSize(data.size))
        var pos = 0
        System.arraycopy(CASCADE_MAGIC, 0, out, pos, CASCADE_MAGIC.size); pos += CASCADE_MAGIC.size
        System.arraycopy(salt, 0, out, pos, salt.size); pos += salt.size
        writeInt(out, pos, FRAME_SIZE); pos += 4
        writeLong(out, pos, data.size.toLong()); pos += 8

        // Scratch for the inner layer: one frame plus its GCM tag, reused throughout.
        val scratch = ByteArray(minOf(FRAME_SIZE, maxOf(data.size, 1)) + AEAD_TAG)

        var offset = 0
        var frameIndex = 0
        do {
            val take = minOf(FRAME_SIZE, data.size - offset)
            val aesNonce = ByteArray(GCM_NONCE_SIZE).apply { random.nextBytes(this) }
            val chachaNonce = ByteArray(CHACHA_NONCE_SIZE).apply { random.nextBytes(this) }
            val keys = frameKeys(master, frameIndex, aesNonce, chachaNonce)

            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            aesCipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keys.aes, "AES"),
                GCMParameterSpec(TAG_SIZE_BITS, aesNonce)
            )
            val layer1Len = aesCipher.doFinal(data, offset, take, scratch, 0)

            System.arraycopy(aesNonce, 0, out, pos, GCM_NONCE_SIZE); pos += GCM_NONCE_SIZE
            System.arraycopy(chachaNonce, 0, out, pos, CHACHA_NONCE_SIZE); pos += CHACHA_NONCE_SIZE
            val lenAt = pos; pos += 4

            val chachaCipher = chaChaPoly1305Cipher()
            chachaCipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keys.chacha, "ChaCha20"),
                IvParameterSpec(chachaNonce)
            )
            val written = chachaCipher.doFinal(scratch, 0, layer1Len, out, pos)
            writeInt(out, lenAt, written)
            pos += written

            offset += take
            frameIndex++
        } while (offset < data.size)

        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(outerMacKey(master), "HmacSHA512"))
        mac.update(out, 0, pos)
        val tag = mac.doFinal()
        System.arraycopy(tag, 0, out, pos, tag.size)
        pos += tag.size

        // Sizing is exact, so this should never trim; keep the guard cheap and loud.
        check(pos == out.size) { "envelope sizing is wrong: wrote $pos of ${out.size}" }
        return out
    }

    // ---- decryption ----

    fun decryptPayload(data: ByteArray, password: String?): ByteArray {
        if (!isEncryptedPayload(data)) return data
        val out = java.io.ByteArrayOutputStream(declaredLength(data).toInt().coerceAtLeast(32))
        decryptTo(data, password, out)
        return out.toByteArray()
    }

    /**
     * Decrypt straight into [out], one frame at a time.
     *
     * This is the form to use when the plaintext is going somewhere other than
     * memory — a file being exported, a socket serving the network drive — since
     * it never materialises the whole file. Peak cost is a single frame.
     *
     * Throws if the envelope fails authentication; nothing is written to [out]
     * before the outer HMAC has been checked over the entire envelope.
     */
    fun decryptTo(data: ByteArray, password: String?, out: OutputStream) {
        if (!isEncryptedPayload(data)) {
            out.write(data)
            return
        }
        if (password.isNullOrBlank()) {
            throw IllegalArgumentException("This payload is encrypted. Password required.")
        }
        if (data.size < HEADER_SIZE + HMAC_SIZE) {
            throw IllegalArgumentException("Corrupted cascade vault payload header.")
        }

        val salt = data.copyOfRange(8, 8 + SALT_SIZE)
        val declaredTotal = declaredLength(data)
        val master = masterKey(password.trim(), salt)

        // Authenticate the whole envelope before decrypting anything, so tampered
        // input never reaches either cipher.
        val macEnd = data.size - HMAC_SIZE
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(outerMacKey(master), "HmacSHA512"))
        mac.update(data, 0, macEnd)
        val expected = mac.doFinal()
        val actual = data.copyOfRange(macEnd, data.size)
        if (!MessageDigest.isEqual(expected, actual)) {
            throw IllegalArgumentException("Decryption failed: incorrect password or tampered payload.")
        }

        var pos = HEADER_SIZE
        var frameIndex = 0
        var written = 0L
        while (pos < macEnd) {
            if (pos + FRAME_HEADER > macEnd) {
                throw IllegalArgumentException("Corrupted cascade vault payload: truncated frame header.")
            }
            val aesNonce = data.copyOfRange(pos, pos + GCM_NONCE_SIZE)
            val chachaNonce = data.copyOfRange(
                pos + GCM_NONCE_SIZE, pos + GCM_NONCE_SIZE + CHACHA_NONCE_SIZE
            )
            val cipherLen = readInt(data, pos + GCM_NONCE_SIZE + CHACHA_NONCE_SIZE)
            pos += FRAME_HEADER
            if (cipherLen < 0 || pos + cipherLen > macEnd) {
                throw IllegalArgumentException("Corrupted cascade vault payload: bad frame length.")
            }

            val keys = frameKeys(master, frameIndex, aesNonce, chachaNonce)

            val chachaCipher = chaChaPoly1305Cipher()
            chachaCipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keys.chacha, "ChaCha20"),
                IvParameterSpec(chachaNonce)
            )
            val layer1 = chachaCipher.doFinal(data, pos, cipherLen)

            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            aesCipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keys.aes, "AES"),
                GCMParameterSpec(TAG_SIZE_BITS, aesNonce)
            )
            val plain = aesCipher.doFinal(layer1)

            out.write(plain)
            written += plain.size
            pos += cipherLen
            frameIndex++
        }

        if (written != declaredTotal) {
            throw IllegalArgumentException(
                "Decryption failed: recovered $written bytes, envelope declares $declaredTotal."
            )
        }
    }

    /** True if [data] carries a cascade envelope, without attempting to open it. */
    fun isEncryptedPayload(data: ByteArray): Boolean =
        data.size >= CASCADE_MAGIC.size &&
            data.copyOfRange(0, CASCADE_MAGIC.size).contentEquals(CASCADE_MAGIC)

    /** Plaintext size an envelope claims to hold, for sizing a destination buffer. */
    fun declaredLength(data: ByteArray): Long {
        if (!isEncryptedPayload(data) || data.size < HEADER_SIZE) return 0
        var v = 0L
        val start = 8 + SALT_SIZE + 4
        for (i in 0 until 8) v = (v shl 8) or (data[start + i].toLong() and 0xFF)
        return v
    }

    // ---- little helpers ----

    private fun intBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun writeInt(dst: ByteArray, at: Int, v: Int) {
        dst[at] = (v ushr 24).toByte()
        dst[at + 1] = (v ushr 16).toByte()
        dst[at + 2] = (v ushr 8).toByte()
        dst[at + 3] = v.toByte()
    }

    private fun writeLong(dst: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) dst[at + i] = (v ushr (56 - i * 8)).toByte()
    }

    private fun readInt(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 24) or
            ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or
            (data[at + 3].toInt() and 0xFF)
}
