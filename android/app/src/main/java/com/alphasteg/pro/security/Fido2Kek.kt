package com.alphasteg.pro.security

import com.yubico.yubikit.fido.client.BasicWebAuthnClient
import com.yubico.yubikit.fido.client.extensions.HmacSecretExtension
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.webauthn.AuthenticatorSelectionCriteria
import com.yubico.yubikit.fido.webauthn.Extensions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialCreationOptions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialDescriptor
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialParameters
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialRequestOptions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialRpEntity
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialUserEntity
import com.yubico.yubikit.fido.webauthn.SerializationType
import java.security.SecureRandom
import java.util.Base64

/**
 * The FIDO2 keyslot's key-wrapping key, produced by a security key's hmac-secret
 * (WebAuthn PRF) extension over NFC or USB.
 *
 * The key computes an HMAC over a salt using a secret that never leaves it, so a
 * given (credential, salt) always yields the same 32 bytes on any device — that is
 * how a YubiKey recovers the vault on a new phone. It is symmetric/hash-based, so
 * it is post-quantum-safe.
 *
 * These calls block on the key (a tap/insert and a user-verification PIN), so run
 * them off the main thread. [enroll] registers a new credential and returns its id
 * plus the KEK for [salt]; [derive] reproduces the KEK for an already-enrolled
 * credential.
 */
object Fido2Kek {

    private const val RP_ID = "alphavault.local"
    private val random = SecureRandom()

    class Enrollment(val credentialId: ByteArray, val kek: ByteArray)

    fun enroll(session: Ctap2Session, pin: CharArray, salt: ByteArray): Enrollment {
        val client = BasicWebAuthnClient(session, listOf(HmacSecretExtension()))
        val rp = PublicKeyCredentialRpEntity("AlphaVault", RP_ID)
        val userId = ByteArray(16).also { random.nextBytes(it) }
        val user = PublicKeyCredentialUserEntity("vault", userId, "AlphaVault Vault")
        val params = listOf(
            PublicKeyCredentialParameters("public-key", -7), // ES256
            PublicKeyCredentialParameters("public-key", -8)  // EdDSA
        )
        // userVerification required so the authenticator gates the hmac-secret on the PIN.
        val selection = AuthenticatorSelectionCriteria(null, null, "required")
        val ext = Extensions.fromMap(mapOf("prf" to emptyMap<String, Any>()))
        val options = PublicKeyCredentialCreationOptions(
            rp, user, challenge(), params, null, null, selection, "none", ext
        )
        val cred = client.makeCredential(clientDataHash(), options, RP_ID, pin, null, null)
        val kek = deriveWith(client, cred.rawId, pin, salt)
        return Enrollment(cred.rawId, kek)
    }

    fun derive(session: Ctap2Session, pin: CharArray, credentialId: ByteArray, salt: ByteArray): ByteArray {
        val client = BasicWebAuthnClient(session, listOf(HmacSecretExtension()))
        return deriveWith(client, credentialId, pin, salt)
    }

    private fun deriveWith(client: BasicWebAuthnClient, credentialId: ByteArray, pin: CharArray, salt: ByteArray): ByteArray {
        val allow = listOf(PublicKeyCredentialDescriptor("public-key", credentialId))
        val ext = Extensions.fromMap(mapOf("prf" to mapOf("eval" to mapOf("first" to salt))))
        val options = PublicKeyCredentialRequestOptions(challenge(), null, RP_ID, allow, "required", ext)
        val cred = client.getAssertion(clientDataHash(), options, RP_ID, pin, null)
        val results = cred.clientExtensionResults?.toMap(SerializationType.CBOR)
            ?: error("Security key returned no PRF result")
        return readPrfFirst(results)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readPrfFirst(results: Map<String, Any>): ByteArray {
        val prf = results["prf"] as? Map<String, Any> ?: error("No prf extension result")
        val res = prf["results"] as? Map<String, Any> ?: error("No prf.results")
        return when (val first = res["first"]) {
            is ByteArray -> first
            is String -> Base64.getUrlDecoder().decode(first)
            else -> error("prf.results.first missing")
        }
    }

    private fun clientDataHash(): ByteArray = ByteArray(32) // hmac-secret output is independent of this
    private fun challenge(): ByteArray = ByteArray(32).also { random.nextBytes(it) }
}
