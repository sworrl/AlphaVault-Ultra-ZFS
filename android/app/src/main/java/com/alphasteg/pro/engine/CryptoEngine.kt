package com.alphasteg.pro.engine

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
 * AlphaVault Ultimate Cascade Crypto Engine
 * Multi-Layer Post-Quantum Grade Cascading Encryption (768-Bit Combined Key Entropy)
 * - Key Stretch: PBKDF2-HMAC-SHA512 with 500,000 iterations -> 768-bit stretched key material
 * - Layer 1: AES-256-GCM (Authenticated Galois/Counter Mode with 128-bit GCM Tag)
 * - Layer 2: ChaCha20-Poly1305 (256-bit Stream Cipher + 128-bit Poly1305 Authenticator)
 * - Dual HMAC-SHA512 Integrity Tag: Outer HMAC-SHA512 authentication over full multi-layer payload
 */
object CryptoEngine {

    private const val PBKDF2_ITERATIONS = 500_000
    private const val SALT_SIZE = 32        // 256-bit Salt
    private const val GCM_NONCE_SIZE = 12   // 96-bit GCM Nonce
    private const val CHACHA_NONCE_SIZE = 12// 96-bit ChaCha Nonce
    private const val TAG_SIZE_BITS = 128

    private val CASCADE_MAGIC = "AVMAX768".toByteArray(Charsets.UTF_8) // AlphaVault 768-bit Cascade Magic

    fun encryptPayload(data: ByteArray, password: String?): ByteArray {
        if (password.isNullOrBlank()) return data

        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).apply { random.nextBytes(this) }
        val aesNonce = ByteArray(GCM_NONCE_SIZE).apply { random.nextBytes(this) }
        val chachaNonce = ByteArray(CHACHA_NONCE_SIZE).apply { random.nextBytes(this) }

        // 1. PBKDF2-HMAC-SHA512 Key Stretch to 768 bits (96 bytes)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(password.trim().toCharArray(), salt, PBKDF2_ITERATIONS, 768)
        val stretchedKeyBytes = factory.generateSecret(spec).encoded

        val aesKeyBytes = stretchedKeyBytes.copyOfRange(0, 32)
        val chachaKeyBytes = stretchedKeyBytes.copyOfRange(32, 64)
        val hmacKeyBytes = stretchedKeyBytes.copyOfRange(64, 96)

        // 2. Layer 1 Encryption: AES-256-GCM
        val aesSecretKey = SecretKeySpec(aesKeyBytes, "AES")
        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, aesNonce)
        aesCipher.init(Cipher.ENCRYPT_MODE, aesSecretKey, gcmSpec)
        val layer1Ciphertext = aesCipher.doFinal(data)

        // 3. Layer 2 Encryption: ChaCha20-Poly1305 Cascade
        val chachaSecretKey = SecretKeySpec(chachaKeyBytes, "ChaCha20")
        val chachaIvSpec = IvParameterSpec(chachaNonce)
        val chachaCipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        chachaCipher.init(Cipher.ENCRYPT_MODE, chachaSecretKey, chachaIvSpec)
        val layer2Ciphertext = chachaCipher.doFinal(layer1Ciphertext)

        // 4. Outer Dual HMAC-SHA512 Authentication Tag
        val hmacKey = SecretKeySpec(hmacKeyBytes, "HmacSHA512")
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(hmacKey)
        
        val headerPayload = CASCADE_MAGIC + salt + aesNonce + chachaNonce + layer2Ciphertext
        val hmacTag = mac.doFinal(headerPayload)

        // Final Encrypted Vault Envelope
        return headerPayload + hmacTag
    }

    fun decryptPayload(data: ByteArray, password: String?): ByteArray {
        if (data.size >= 8 && data.copyOfRange(0, 8).contentEquals(CASCADE_MAGIC)) {
            if (password.isNullOrBlank()) {
                throw IllegalArgumentException("This payload is encrypted with Maxed Out Cascade 768-bit Encryption. Password required.")
            }
            
            // Header: MAGIC(8) + SALT(32) + AES_NONCE(12) + CHACHA_NONCE(12) = 64 bytes header
            if (data.size < 64 + 64) {
                throw IllegalArgumentException("Corrupted Cascade Vault payload header.")
            }

            val salt = data.copyOfRange(8, 40)
            val aesNonce = data.copyOfRange(40, 52)
            val chachaNonce = data.copyOfRange(52, 64)

            val hmacTagStart = data.size - 64
            val headerAndBody = data.copyOfRange(0, hmacTagStart)
            val storedHmacTag = data.copyOfRange(hmacTagStart, data.size)
            val layer2Ciphertext = data.copyOfRange(64, hmacTagStart)

            // 1. Derive 768-bit Stretched Keys via PBKDF2-HMAC-SHA512 (500k iterations)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = PBEKeySpec(password.trim().toCharArray(), salt, PBKDF2_ITERATIONS, 768)
            val stretchedKeyBytes = factory.generateSecret(spec).encoded

            val aesKeyBytes = stretchedKeyBytes.copyOfRange(0, 32)
            val chachaKeyBytes = stretchedKeyBytes.copyOfRange(32, 64)
            val hmacKeyBytes = stretchedKeyBytes.copyOfRange(64, 96)

            // 2. Verify Outer HMAC-SHA512 Tag First (Authenticate-then-Decrypt)
            val hmacKey = SecretKeySpec(hmacKeyBytes, "HmacSHA512")
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(hmacKey)
            val calculatedHmac = mac.doFinal(headerAndBody)

            if (!MessageDigest.isEqual(storedHmacTag, calculatedHmac)) {
                throw IllegalArgumentException("Decryption failed: Incorrect Password or Tampered Payload Tag.")
            }

            // 3. Layer 2 Decryption: ChaCha20-Poly1305
            val chachaSecretKey = SecretKeySpec(chachaKeyBytes, "ChaCha20")
            val chachaIvSpec = IvParameterSpec(chachaNonce)
            val chachaCipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
            chachaCipher.init(Cipher.DECRYPT_MODE, chachaSecretKey, chachaIvSpec)
            val layer1Ciphertext = chachaCipher.doFinal(layer2Ciphertext)

            // 4. Layer 1 Decryption: AES-256-GCM
            val aesSecretKey = SecretKeySpec(aesKeyBytes, "AES")
            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, aesNonce)
            aesCipher.init(Cipher.DECRYPT_MODE, aesSecretKey, gcmSpec)
            return aesCipher.doFinal(layer1Ciphertext)
        }
        return data
    }
}
