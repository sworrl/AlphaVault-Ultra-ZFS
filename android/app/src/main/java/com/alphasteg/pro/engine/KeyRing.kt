package com.alphasteg.pro.engine

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/**
 * One dataset, many codes.
 *
 * A dataset is encrypted under a single random 256-bit data key (the DEK). The
 * DEK itself never touches disk in the clear; instead it is wrapped once per code
 * that may open the dataset. Each wrap is an independent cascade envelope
 * (`CryptoEngine`), so a code reveals only whether it opens the dataset, and
 * nothing about the other codes or how many exist.
 *
 * A grant may be one-time. When a one-time code opens the dataset, its wrap is
 * removed from the ring; persisting the returned ring burns that code for good.
 * This is the journalist model: hand out a set of one-time codes, each good for a
 * single unlock, after which it opens nothing.
 *
 * The ring holds no verifier. A code that matches no wrap returns null, the same
 * answer an empty ring gives, so the ring does not reveal that any code is close.
 */
class KeyRing private constructor(
    private val grants: List<Grant>
) {

    /** A single wrap of the DEK under one code. `wrapped` is a cascade envelope. */
    private class Grant(
        val label: String,
        val oneTime: Boolean,
        val wrapped: ByteArray
    )

    /** Result of opening the ring: the DEK plus the ring to persist afterwards. */
    class Opened(
        /** Base64 data key; use as the password for the dataset's file payloads. */
        val dek: String,
        /** The label of the grant that opened it. */
        val grantLabel: String,
        /** True if that grant was one-time and has now been removed. */
        val burned: Boolean,
        /** The ring to store going forward; differs from the opened ring only
         *  when a one-time grant was burned. */
        val ring: KeyRing
    )

    /** Number of codes that can currently open this ring. */
    val grantCount: Int get() = grants.size

    /** Labels of the current grants, in order, for a management UI. */
    fun grantLabels(): List<String> = grants.map { it.label }

    /**
     * Add another code that opens the same DEK. Returns a new ring; rings are
     * immutable so a caller cannot half-apply a change. [dek] is the value from
     * [Opened.dek] (or from [create]); it is not stored, only re-wrapped.
     */
    fun addGrant(dek: String, code: String, label: String, oneTime: Boolean): KeyRing {
        require(code.isNotBlank()) { "A grant code cannot be blank." }
        require(grants.none { it.label == label }) { "Duplicate grant label: $label" }
        val wrapped = CryptoEngine.encryptPayload(dek.toByteArray(Charsets.UTF_8), code)
        return KeyRing(grants + Grant(label, oneTime, wrapped))
    }

    /** Remove a grant by label (revoke a code). Returns a new ring. */
    fun removeGrant(label: String): KeyRing =
        KeyRing(grants.filterNot { it.label == label })

    /**
     * Try [code] against every wrap. Returns the DEK and the ring to persist, or
     * null if no wrap opens. A one-time grant is removed from the returned ring;
     * the caller must store [Opened.ring] for the burn to take effect.
     */
    fun open(code: String): Opened? {
        if (code.isBlank()) return null
        for (g in grants) {
            val dek = tryUnwrap(g.wrapped, code) ?: continue
            val nextRing = if (g.oneTime) KeyRing(grants.filterNot { it === g }) else this
            return Opened(dek, g.label, g.oneTime, nextRing)
        }
        return null
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (g in grants) {
            arr.put(JSONObject().apply {
                put("label", g.label)
                put("oneTime", g.oneTime)
                put("wrapped", Base64.getEncoder().encodeToString(g.wrapped))
            })
        }
        return JSONObject().apply {
            put("v", 1)
            put("grants", arr)
        }.toString()
    }

    companion object {
        private val random = SecureRandom()

        /**
         * Start a new dataset. Returns the fresh DEK (keep it only long enough to
         * add the first grant) and an empty ring. A ring with no grants can never
         * be opened, so add at least one grant before persisting.
         */
        fun create(): Pair<String, KeyRing> {
            val raw = ByteArray(32).also { random.nextBytes(it) }
            val dek = Base64.getEncoder().encodeToString(raw)
            return dek to KeyRing(emptyList())
        }

        fun fromJson(json: String): KeyRing {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("grants") ?: JSONArray()
            val grants = ArrayList<Grant>(arr.length())
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                grants.add(
                    Grant(
                        label = g.getString("label"),
                        oneTime = g.optBoolean("oneTime", false),
                        wrapped = Base64.getDecoder().decode(g.getString("wrapped"))
                    )
                )
            }
            return KeyRing(grants)
        }

        private fun tryUnwrap(wrapped: ByteArray, code: String): String? =
            try {
                String(CryptoEngine.decryptPayload(wrapped, code), Charsets.UTF_8)
            } catch (e: Exception) {
                // Wrong code fails the cascade's HMAC; that is a miss, not an error.
                null
            }
    }
}
