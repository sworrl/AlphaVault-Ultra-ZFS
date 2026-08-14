package com.alphasteg.pro.net

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * One-file, time-limited capability tokens for casting to a DLNA renderer.
 *
 * Why this exists at all: the network drive is protected with HTTP Basic auth,
 * but a DLNA renderer is handed a bare URL and will not send credentials, so a
 * cast needs a URL that authenticates by itself. That is a genuine weakening —
 * URLs end up in renderer logs and "recently played" lists — so the design gives
 * away as little as possible:
 *
 *  - A grant covers exactly one file, never the vault or a folder. A leaked cast
 *    URL exposes the track you deliberately cast and nothing else.
 *  - Tokens are 256 bits of [SecureRandom], so they cannot be guessed or walked.
 *  - Grants expire on a timer and are all dropped when the vault locks.
 *  - Nothing is issued unless the user explicitly casts something.
 *
 * The decrypted bytes are held alongside the grant so range requests (renderers
 * seek constantly) do not re-run the whole restore for every chunk. That buffer
 * is zeroed on revoke rather than merely dropped.
 */
class CastGrants(private val clock: () -> Long = System::currentTimeMillis) {

    data class Grant(
        val token: String,
        val fileId: String,
        val name: String,
        val contentType: String,
        val expiresAt: Long
    )

    private val grants = ConcurrentHashMap<String, Grant>()
    private val buffers = ConcurrentHashMap<String, ByteArray>()
    private val random = SecureRandom()

    /** Issue a capability for one file. [ttlMs] defaults to two hours. */
    fun issue(
        fileId: String,
        name: String,
        contentType: String,
        ttlMs: Long = DEFAULT_TTL_MS
    ): Grant {
        val raw = ByteArray(32).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val grant = Grant(token, fileId, name, contentType, clock() + ttlMs)
        grants[token] = grant
        return grant
    }

    /**
     * The grant a token names, or null if it is unknown or expired. The lookup is
     * a constant-time scan rather than a hash probe so a token cannot be recovered
     * by timing; the grant count is always tiny.
     */
    fun resolve(token: String): Grant? {
        if (token.isEmpty()) return null
        val candidate = token.toByteArray(Charsets.UTF_8)
        var found: Grant? = null
        for ((key, grant) in grants) {
            if (MessageDigest.isEqual(key.toByteArray(Charsets.UTF_8), candidate)) found = grant
        }
        val g = found ?: return null
        if (clock() >= g.expiresAt) { revoke(g.token); return null }
        return g
    }

    /** Cache the decrypted file for a grant, so seeks do not re-restore it. */
    fun cache(token: String, bytes: ByteArray) {
        if (grants.containsKey(token)) buffers[token] = bytes
    }

    fun cached(token: String): ByteArray? = buffers[token]

    fun revoke(token: String) {
        grants.remove(token)
        buffers.remove(token)?.fill(0)
    }

    /** Drop every capability. Called when the vault locks or the server stops. */
    fun revokeAll() {
        grants.clear()
        for (key in buffers.keys.toList()) buffers.remove(key)?.fill(0)
    }

    /** Discard anything already past its expiry, zeroing buffers as it goes. */
    fun purgeExpired() {
        val now = clock()
        for ((token, grant) in grants) if (now >= grant.expiresAt) revoke(token)
    }

    fun activeCount(): Int = grants.size

    companion object {
        const val DEFAULT_TTL_MS = 2 * 60 * 60 * 1000L

        /** URL path a renderer is handed. Kept distinct from the browsable tree. */
        const val PREFIX = "/cast/"

        /** The token in a `/cast/<token>/<name>` path, or null if not a cast URL. */
        fun tokenFromPath(rawPath: String): String? {
            val path = rawPath.substringBefore('?')
            if (!path.startsWith(PREFIX)) return null
            val rest = path.removePrefix(PREFIX)
            val token = rest.substringBefore('/')
            return token.takeIf { it.isNotEmpty() }
        }
    }
}
