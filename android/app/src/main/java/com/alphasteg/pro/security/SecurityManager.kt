package com.alphasteg.pro.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Master and duress credentials for the vault.
 *
 * Codes are 8+ characters from the keypad alphabet (0-9, a-f, symbols). Two
 * distinct codes are set at onboarding: the master code unlocks the vault; the
 * duress code triggers a wipe.
 *
 * Only a slow, salted PBKDF2-HMAC-SHA512 verifier is stored, with a random
 * per-install salt. This matters for the threat model where an attacker has the
 * source code and the prefs file: the verifier is not a fast oracle, and it
 * never protects the data anyway (files are separately encrypted with the code
 * via CryptoEngine). Confidentiality rests entirely on the code's entropy.
 *
 * Biometric unlock was removed: Android's BiometricPrompt cannot tell the app
 * which finger authenticated, so a specific finger cannot be bound to duress.
 */
class SecurityManager(context: Context) {

    private val prefs = context.getSharedPreferences("alphasteg_vault_sec", Context.MODE_PRIVATE)

    enum class AuthResult { SUCCESS_MASTER, SUCCESS_DURESS, INVALID }

    fun isVaultSetup(): Boolean = prefs.contains(PREF_PIN_HASH)

    /** Set the master and duress codes. Both must be valid, >= 8, and differ. */
    fun setupCredentials(masterPin: String, duressPin: String) {
        require(isValidPin(masterPin)) { "Master code must be at least $MIN_LEN characters." }
        require(isValidPin(duressPin)) { "Duress code must be at least $MIN_LEN characters." }
        require(!masterPin.equals(duressPin, ignoreCase = true)) { "Duress code must differ from the master code." }
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(PREF_SALT, b64(salt))
            .putString(PREF_PIN_HASH, verifier(masterPin, salt))
            .putString(PREF_DURESS_HASH, verifier(duressPin, salt))
            .putInt(PREF_MASTER_LEN, masterPin.length)
            .putInt(PREF_DURESS_LEN, duressPin.length)
            .apply()
    }

    fun verifyPin(inputPin: String): AuthResult {
        val salt = storedSalt() ?: return AuthResult.INVALID
        val h = verifier(inputPin, salt)
        return when (h) {
            prefs.getString(PREF_PIN_HASH, null) -> AuthResult.SUCCESS_MASTER
            prefs.getString(PREF_DURESS_HASH, null) -> AuthResult.SUCCESS_DURESS
            else -> AuthResult.INVALID
        }
    }

    data class Match(val result: AuthResult, val code: String)

    /**
     * Scan an input string (e.g. a calculator expression) for the master or
     * duress code as a substring. Only substrings of the two known code lengths
     * are tested, so this is O(n) PBKDF2 checks rather than O(n^2). Duress wins.
     * Meant to be called off the main thread. Used by the calculator disguise.
     */
    fun findCode(input: String): Match? {
        val salt = storedSalt() ?: return null
        val masterHash = prefs.getString(PREF_PIN_HASH, null) ?: return null
        val duressHash = prefs.getString(PREF_DURESS_HASH, null)
        val lengths = setOf(prefs.getInt(PREF_MASTER_LEN, 0), prefs.getInt(PREF_DURESS_LEN, 0)).filter { it >= MIN_LEN }
        var master: String? = null
        for (len in lengths) {
            if (input.length < len) continue
            for (start in 0..(input.length - len)) {
                val candidate = input.substring(start, start + len)
                val h = verifier(candidate, salt)
                if (h == duressHash) return Match(AuthResult.SUCCESS_DURESS, candidate)
                if (h == masterHash && master == null) master = candidate
            }
        }
        return master?.let { Match(AuthResult.SUCCESS_MASTER, it) }
    }

    /** Erase stored credentials, forcing re-onboarding. Part of the duress path. */
    fun wipeCredentials() {
        prefs.edit()
            .remove(PREF_PIN_HASH).remove(PREF_DURESS_HASH).remove(PREF_SALT)
            .remove(PREF_MASTER_LEN).remove(PREF_DURESS_LEN)
            .apply()
    }

    fun isValidPin(pin: String): Boolean =
        pin.length >= MIN_LEN && pin.all { it in ALPHABET_SET }

    private fun storedSalt(): ByteArray? =
        prefs.getString(PREF_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Slow, salted PBKDF2-HMAC-SHA512 verifier (not a fast offline oracle). */
    private fun verifier(pin: String, salt: ByteArray): String {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(pin.lowercase().toCharArray(), salt, VERIFIER_ITERATIONS, 256)
        return b64(factory.generateSecret(spec).encoded)
    }

    companion object {
        const val MIN_LEN = 8

        /** Keypad alphabet: 16 hex digits plus 8 symbols (24 keys). */
        const val ALPHABET = "0123456789abcdef!@#\$%&*?"

        private const val SALT_LEN = 16
        private const val VERIFIER_ITERATIONS = 120_000
        private const val PREF_SALT = "cred_salt"
        private const val PREF_MASTER_LEN = "master_len"
        private const val PREF_DURESS_LEN = "duress_len"

        private const val PREF_PIN_HASH = "master_pin_hash"
        private const val PREF_DURESS_HASH = "duress_pin_hash"
        private val ALPHABET_SET = ALPHABET.toSet()
    }
}
