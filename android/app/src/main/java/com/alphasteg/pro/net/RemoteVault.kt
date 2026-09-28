package com.alphasteg.pro.net

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Reads a vault that another device is serving over Wi-Fi.
 *
 * The counterpart to [VaultWebServer]: the phone holds the carriers and does the
 * decryption, and a second device - typically the DAP, which has the good DAC but
 * not the storage - browses and plays what the phone has unlocked. Nothing is
 * decrypted here and no carriers are touched; this speaks plain HTTP to a server
 * that has already done that work.
 *
 * Because the server is read-only and token-gated, so is this. Every request
 * carries HTTP Basic credentials, and without the session token the far end
 * answers 401 and never any content.
 *
 * All calls block on network I/O and must run off the main thread.
 */
class RemoteVault(
    private val host: String,
    private val port: Int,
    private val token: String
) {

    /** One entry in a remote folder. */
    data class Item(
        val name: String,
        val path: String,
        val isFolder: Boolean,
        val size: Long
    )

    private val base get() = "http://$host:$port"

    private val authHeader: String
        get() = "Basic " + Base64.getEncoder()
            .encodeToString("${VaultDav.USER}:$token".toByteArray(Charsets.UTF_8))

    /**
     * List [path].
     *
     * Asks for the JSON listing over a plain GET rather than sending WebDAV's
     * PROPFIND: [java.net.HttpURLConnection] validates the request method against
     * a fixed list and throws on anything else, so PROPFIND is simply not
     * reachable from here. The server still speaks PROPFIND for desktops mounting
     * it as a drive.
     */
    fun list(path: String = "/"): List<Item> {
        val body = getText(path, listing = true) ?: return emptyList()
        return parseListing(body)
    }

    /** True if the token opens this vault, so a wrong code is reported as such. */
    fun canConnect(): Boolean = getText("/", listing = true) != null

    /**
     * Fetch one file into [sink]. Streams straight through, so a large file does
     * not have to be held in memory on the way past.
     */
    fun fetch(path: String, sink: OutputStream): Boolean {
        val conn = open("GET", path) ?: return false
        return try {
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                }
            }
            sink.flush()
            true
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /** The URL a media player can be pointed at, credentials included. */
    fun streamUrl(path: String): String = base + encodePath(path)

    // ---- HTTP ----

    private fun open(method: String, path: String, query: String = ""): HttpURLConnection? = runCatching {
        (URL(base + encodePath(path) + query).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 15000
            setRequestProperty("Authorization", authHeader)
            setRequestProperty("Connection", "close")
        }
    }.getOrNull()

    private fun getText(path: String, listing: Boolean): String? = runCatching {
        val conn = open("GET", path, if (listing) "?${VaultDav.JSON_QUERY}" else "") ?: return null
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    companion object {

        /**
         * Read a folder listing produced by [VaultDav.jsonListing].
         *
         * Anything malformed yields an empty list rather than throwing: the body
         * arrived over the network and a half-written or hostile response must not
         * take the browser down.
         */
        fun parseListing(json: String): List<Item> {
            val out = ArrayList<Item>()
            runCatching {
                val items = org.json.JSONObject(json).getJSONArray("items")
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    val name = o.optString("name").takeIf { it.isNotEmpty() } ?: continue
                    val path = o.optString("path").takeIf { it.isNotEmpty() } ?: continue
                    out.add(Item(name, path, o.optBoolean("folder", false), o.optLong("size", 0)))
                }
            }
            return out.sortedWith(compareByDescending<Item> { it.isFolder }.thenBy { it.name.lowercase() })
        }
    }
}
