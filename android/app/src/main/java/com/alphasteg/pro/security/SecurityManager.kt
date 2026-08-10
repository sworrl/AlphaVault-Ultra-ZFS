package com.alphasteg.pro.security

import android.content.Context
import java.security.MessageDigest

/**
 * Master and duress credentials for the vault.
 *
 * Codes are hexadecimal (0-9, a-f), at least 8 digits. Two distinct codes are
 * set at onboarding: the master code unlocks the vault; the duress code triggers
 * a wipe. Codes are stored only as salted SHA-256 hashes.
 *
 * Biometric unlock was removed: Android's BiometricPrompt cannot tell the app
 * which finger authenticated, so a specific finger cannot be bound to duress.
 */
class SecurityManager(context: Context) {

    private val prefs = context.getSharedPreferences("alphasteg_vault_sec", Context.MODE_PRIVATE)

    enum class AuthResult { SUCCESS_MASTER, SUCCESS_DURESS, INVALID }

    fun isVaultSetup(): Boolean = prefs.contains(PREF_PIN_HASH)

    /** Set the master and duress codes. Both must be valid hex, >= 8, and differ. */
    fun setupCredentials(masterPin: String, duressPin: String) {
        require(isValidPin(masterPin)) { "Master code must be at least $MIN_LEN hex digits." }
        require(isValidPin(duressPin)) { "Duress code must be at least $MIN_LEN hex digits." }
        require(!masterPin.equals(duressPin, ignoreCase = true)) { "Duress code must differ from the master code." }
        prefs.edit()
            .putString(PREF_PIN_HASH, hashPin(masterPin))
            .putString(PREF_DURESS_HASH, hashPin(duressPin))
            .apply()
    }

    fun verifyPin(inputPin: String): AuthResult {
        val inputHash = hashPin(inputPin)
        return when (inputHash) {
            prefs.getString(PREF_PIN_HASH, null) -> AuthResult.SUCCESS_MASTER
            prefs.getString(PREF_DURESS_HASH, null) -> AuthResult.SUCCESS_DURESS
            else -> AuthResult.INVALID
        }
    }

    /** Erase stored credentials, forcing re-onboarding. Part of the duress path. */
    fun wipeCredentials() {
        prefs.edit().remove(PREF_PIN_HASH).remove(PREF_DURESS_HASH).apply()
    }

    fun isValidPin(pin: String): Boolean =
        pin.length >= MIN_LEN && pin.all { it in HEX_CHARS }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("AlphaVault_Salt_2026_${pin.lowercase()}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MIN_LEN = 8
        private const val PREF_PIN_HASH = "master_pin_hash"
        private const val PREF_DURESS_HASH = "duress_pin_hash"
        private val HEX_CHARS = "0123456789abcdef".toSet()
    }
}
