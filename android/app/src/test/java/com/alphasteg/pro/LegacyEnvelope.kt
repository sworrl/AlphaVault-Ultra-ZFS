package com.alphasteg.pro

/** Writes the legacy envelope, which production code only reads, for tests. */
object LegacyEnvelope {

    /** An AVMAX768 envelope built exactly as the Aug 10, 2026 builds wrote them. */
    fun seal(data: ByteArray, password: String): ByteArray {
        val rnd = java.security.SecureRandom()
        val salt = ByteArray(32).also { rnd.nextBytes(it) }
        val aesNonce = ByteArray(12).also { rnd.nextBytes(it) }
        val chachaNonce = ByteArray(12).also { rnd.nextBytes(it) }
        val keys = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(javax.crypto.spec.PBEKeySpec(password.trim().toCharArray(), salt, 500_000, 768)).encoded
        val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(keys.copyOfRange(0, 32), "AES"),
            javax.crypto.spec.GCMParameterSpec(128, aesNonce))
        val chacha = runCatching { javax.crypto.Cipher.getInstance("ChaCha20/Poly1305/NoPadding") }
            .getOrElse { javax.crypto.Cipher.getInstance("ChaCha20-Poly1305") }
        chacha.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(keys.copyOfRange(32, 64), "ChaCha20"),
            javax.crypto.spec.IvParameterSpec(chachaNonce))
        val body = "AVMAX768".toByteArray() + salt + aesNonce + chachaNonce + chacha.doFinal(aes.doFinal(data))
        val mac = javax.crypto.Mac.getInstance("HmacSHA512")
        mac.init(javax.crypto.spec.SecretKeySpec(keys.copyOfRange(64, 96), "HmacSHA512"))
        return body + mac.doFinal(body)
    }
}
