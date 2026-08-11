package com.alphasteg.pro.engine

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LUKS-style keyslots around one data key (DEK).
 *
 * The DEK encrypts the vault. It is never stored in the clear; instead it is
 * wrapped into one or more independent slots, each unwrappable by a different
 * key-wrapping key (KEK). The KEK comes from a factor outside this class:
 *
 *  - [Kind.STRONGBOX] : a non-exportable key in the phone's secure chip.
 *  - [Kind.FIDO2]     : a security key's FIDO2 hmac-secret (NFC or USB), which
 *                       re-derives the same KEK on any device, so it recovers the
 *                       vault after a phone is lost.
 *  - [Kind.OTP]       : a one-time code, burned after a single successful open.
 *  - [Kind.CODE]      : a code-derived KEK (software), for dev/software fallback.
 *
 * This class is pure and hardware-agnostic: it takes a 32-byte KEK, wraps/unwraps
 * the DEK with AES-256-GCM, and manages the slot set. The StrongBox and FIDO2
 * adapters live elsewhere and only produce KEKs. A slot's [Slot.slotData] is an
 * opaque blob those adapters use to reproduce the KEK (a key salt, a FIDO
 * credential id, etc.); this class never interprets it.
 *
 * Any single slot opens the DEK. Lose every slot's factor and the DEK is
 * unrecoverable: the data is gone.
 */
class VaultKeystore private constructor(private val slots: List<Slot>) {

    enum class Kind { CODE, STRONGBOX, FIDO2, OTP }

    class Slot(
        val id: String,
        val kind: Kind,
        val label: String,
        val oneTime: Boolean,
        val slotData: ByteArray,   // opaque; the KEK-producing adapter's own data
        val wrapped: ByteArray     // AES-256-GCM(DEK) under this slot's KEK
    )

    class Opened(
        val dek: ByteArray,
        val slotId: String,
        val kind: Kind,
        val burned: Boolean,
        val keystore: VaultKeystore
    )

    val slotCount: Int get() = slots.size

    data class SlotInfo(val id: String, val kind: Kind, val label: String, val oneTime: Boolean)
    fun slotInfo(): List<SlotInfo> = slots.map { SlotInfo(it.id, it.kind, it.label, it.oneTime) }
    fun slotData(id: String): ByteArray? = slots.firstOrNull { it.id == id }?.slotData

    /** Add a slot that wraps [dek] under [kek]. Returns a new keystore. */
    fun addSlot(dek: ByteArray, kek: ByteArray, kind: Kind, label: String, oneTime: Boolean, slotData: ByteArray = ByteArray(0)): VaultKeystore {
        require(dek.size == 32) { "DEK must be 32 bytes." }
        require(kek.size == 32) { "KEK must be 32 bytes." }
        require(slots.none { it.label == label && it.kind == kind }) { "Duplicate slot: $kind/$label" }
        val id = randomId()
        return VaultKeystore(slots + Slot(id, kind, label, oneTime, slotData, wrap(dek, kek)))
    }

    /**
     * Try [kek] against the slot [slotId]. Returns the DEK (and a keystore with the
     * slot burned if it was one-time) or null if this KEK does not open that slot.
     * The caller pairs a slot with the KEK its adapter produced from [slotData].
     */
    fun openSlot(slotId: String, kek: ByteArray): Opened? {
        val slot = slots.firstOrNull { it.id == slotId } ?: return null
        val dek = unwrap(slot.wrapped, kek) ?: return null
        val next = if (slot.oneTime) VaultKeystore(slots.filterNot { it.id == slotId }) else this
        return Opened(dek, slot.id, slot.kind, slot.oneTime, next)
    }

    /** Try [kek] against every slot (used when a KEK is not tied to one slot). */
    fun open(kek: ByteArray): Opened? {
        for (s in slots) openSlot(s.id, kek)?.let { return it }
        return null
    }

    fun removeSlot(id: String): VaultKeystore = VaultKeystore(slots.filterNot { it.id == id })

    fun toJson(): String {
        val arr = JSONArray()
        for (s in slots) arr.put(JSONObject().apply {
            put("id", s.id); put("kind", s.kind.name); put("label", s.label); put("oneTime", s.oneTime)
            put("slotData", b64(s.slotData)); put("wrapped", b64(s.wrapped))
        })
        return JSONObject().put("v", 1).put("slots", arr).toString()
    }

    companion object {
        private val random = SecureRandom()
        private const val NONCE = 12
        private const val TAG_BITS = 128

        /** Start a vault with a fresh random DEK and no slots. Add at least one before use. */
        fun create(): Pair<ByteArray, VaultKeystore> {
            val dek = ByteArray(32).also { random.nextBytes(it) }
            return dek to VaultKeystore(emptyList())
        }

        fun fromJson(json: String): VaultKeystore {
            val arr = JSONObject(json).optJSONArray("slots") ?: JSONArray()
            val slots = ArrayList<Slot>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                slots.add(Slot(
                    o.getString("id"), Kind.valueOf(o.getString("kind")), o.getString("label"),
                    o.optBoolean("oneTime", false),
                    Base64.getDecoder().decode(o.optString("slotData", "")),
                    Base64.getDecoder().decode(o.getString("wrapped"))
                ))
            }
            return VaultKeystore(slots)
        }

        /** Derive a 32-byte KEK from a code + salt (for CODE and OTP slots). */
        fun kekFromCode(code: String, salt: ByteArray): ByteArray {
            val spec = javax.crypto.spec.PBEKeySpec(code.toCharArray(), salt, 200_000, 256)
            return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        }

        fun randomSalt(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

        private fun randomId(): String = b64(ByteArray(9).also { random.nextBytes(it) })
        private fun b64(b: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(b)

        private fun wrap(dek: ByteArray, kek: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            return nonce + c.doFinal(dek)
        }

        private fun unwrap(wrapped: ByteArray, kek: ByteArray): ByteArray? = runCatching {
            val nonce = wrapped.copyOfRange(0, NONCE)
            val body = wrapped.copyOfRange(NONCE, wrapped.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            c.doFinal(body)
        }.getOrNull()?.takeIf { it.size == 32 }
    }
}
