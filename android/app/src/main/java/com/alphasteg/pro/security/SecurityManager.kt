package com.alphasteg.pro.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecurityManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("alphasteg_vault_sec", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "AlphaStegMasterKey"
        private const val PREF_PIN_HASH = "master_pin_hash"
        private const val PREF_DECOY_PIN_HASH = "decoy_pin_hash"
        private const val PREF_AUTH_TYPE = "auth_type" // PIN, PASSWORD, PATTERN
    }

    init {
        ensureHardwareKeyExists()
    }

    private fun ensureHardwareKeyExists() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            keyGenerator.generateKey()
        }
    }

    fun isVaultSetup(): Boolean {
        return prefs.contains(PREF_PIN_HASH)
    }

    fun setupMasterPin(pin: String, decoyPin: String? = null) {
        val hash = hashPin(pin)
        prefs.edit().putString(PREF_PIN_HASH, hash).apply()
        
        if (!decoyPin.isNullOrBlank()) {
            val decoyHash = hashPin(decoyPin)
            prefs.edit().putString(PREF_DECOY_PIN_HASH, decoyHash).apply()
        }
    }

    fun verifyPin(inputPin: String): AuthResult {
        val inputHash = hashPin(inputPin)
        val storedHash = prefs.getString(PREF_PIN_HASH, "")
        val storedDecoyHash = prefs.getString(PREF_DECOY_PIN_HASH, "")

        return when {
            inputHash == storedHash -> AuthResult.SUCCESS_MASTER
            inputHash == storedDecoyHash -> AuthResult.SUCCESS_DECOY
            else -> AuthResult.INVALID
        }
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("AlphaVault_Salt_2026_$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    enum class AuthResult {
        SUCCESS_MASTER,
        SUCCESS_DECOY,
        INVALID
    }
}
