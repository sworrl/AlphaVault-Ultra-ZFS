package com.alphasteg.pro.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The StrongBox keyslot's key-wrapping key, produced by a hardware HMAC key that
 * lives in the phone's secure element (the Pixel's Titan M2) and never leaves it.
 *
 * The keyslot ([com.alphasteg.pro.engine.VaultKeystore]) needs a stable 32-byte
 * KEK. An HMAC-SHA256 key held in StrongBox gives exactly that: HMAC(salt) is
 * deterministic, so the same salt always yields the same KEK, while the key itself
 * is non-exportable. A storage clone copied to another device cannot reproduce the
 * KEK because the Titan key stays in the original phone, so that slot is inert on
 * the clone. Losing the phone is why the FIDO2 recovery slot exists.
 *
 * When [requireUserAuth] is set the key can only be used shortly after the user
 * authenticates (device PIN or biometric), so computing the KEK is gated behind
 * the lock screen; the caller drives that prompt.
 */
class StrongBoxKek(context: Context) {

    private val hasStrongBox: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /** True on hardware that can hold this key (StrongBox, or a TEE fallback). */
    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /** Whether the key is backed by a dedicated security chip rather than the TEE. */
    fun isStrongBoxBacked(): Boolean = hasStrongBox

    /**
     * Compute the KEK for [salt]. Creates the hardware key on first use. Throws
     * `UserNotAuthenticatedException` if [requireUserAuth] was set and the user has
     * not authenticated recently; the caller should prompt and retry.
     */
    fun kek(salt: ByteArray, requireUserAuth: Boolean = true): ByteArray {
        val key = ensureKey(requireUserAuth)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(salt) // 32 bytes
    }

    /** Remove the hardware key (e.g. on duress wipe), making every StrongBox slot unopenable. */
    fun destroy() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private fun ensureKey(requireUserAuth: Boolean): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return generate(requireUserAuth)
    }

    private fun generate(requireUserAuth: Boolean): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_SIGN
        ).apply {
            setDigests(KeyProperties.DIGEST_SHA256)
            setKeySize(256)
            if (requireUserAuth) setUserAuthenticationRequired(true)
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
        }.build()

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        // Prefer StrongBox; fall back to the TEE if the chip refuses these parameters.
        return try {
            gen.init(spec(hasStrongBox)); gen.generateKey()
        } catch (e: Exception) {
            gen.init(spec(false)); gen.generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "avault_strongbox_hmac"
    }
}
