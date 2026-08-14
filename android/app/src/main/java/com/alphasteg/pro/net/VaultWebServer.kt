package com.alphasteg.pro.net

import com.alphasteg.pro.data.VaultFs
import com.alphasteg.pro.data.VaultVolume
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Read-only network drive for the unlocked vault. Speaks enough HTTP + WebDAV
 * (OPTIONS, PROPFIND, HEAD, GET) that a computer on the same Wi-Fi can mount it as
 * a drive or browse it in a browser, decrypting files on the fly.
 *
 * Safety:
 *  - Only runs while the vault is unlocked; it holds the session password in
 *    memory and is stopped when the vault locks.
 *  - Every request needs HTTP Basic auth with the per-session [token]; without it
 *    the server returns 401 and never any content.
 *  - Read-only: no PUT/DELETE/MKCOL, so a client cannot alter the vault.
 */
class VaultWebServer(
    private val vault: VaultVolume,
    private val poolProvider: () -> List<File>,
    private val password: String,
    val token: String,
    /** Single-file capabilities for DLNA casting; see [CastGrants]. */
    val grants: CastGrants = CastGrants(),
    val port: Int = 8973
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(4)

    fun start() {
        if (running) return
        running = true
        Thread {
            runCatching {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (running) {
                    val socket = runCatching { ss.accept() }.getOrNull() ?: break
                    workers.submit { runCatching { handle(socket) }; runCatching { socket.close() } }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        // Drop every cast capability and zero the plaintext they cached, so a
        // lock leaves no reachable URL and no decrypted bytes behind.
        runCatching { grants.revokeAll() }
        runCatching { serverSocket?.close() }
        workers.shutdownNow()
    }

    private fun handle(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val rawPath = parts[1]

        var auth: String? = null
        var depth = 1
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val lower = line.lowercase()
            when {
                lower.startsWith("authorization:") -> auth = line.substringAfter(':').trim()
                lower.startsWith("depth:") -> depth = line.substringAfter(':').trim().toIntOrNull() ?: 1
                lower.startsWith("range:") -> range = line.substringAfter(':').trim()
            }
        }

        val out = socket.getOutputStream()

        // A cast URL carries its own single-file capability, because a DLNA
        // renderer is handed a bare URL and will not send Basic credentials.
        // It is answered before the auth check and can reach exactly one file.
        val castToken = CastGrants.tokenFromPath(rawPath)
        if (castToken != null) {
            serveCast(out, castToken, method, range)
            return
        }

        if (!VaultDav.authOk(auth, token)) {
            writeHead(out, "401 Unauthorized", mapOf("WWW-Authenticate" to "Basic realm=\"AlphaVault\""))
            return
        }

        val pool = poolProvider()
        val index = runCatching { vault.loadIndex(pool, password) }.getOrDefault(VaultVolume.Index(0, emptyList()))

        when (method) {
            "OPTIONS" -> writeHead(out, "200 OK", mapOf(
                "DAV" to "1", "Allow" to "OPTIONS, GET, HEAD, PROPFIND", "Content-Length" to "0"
            ))
            "PROPFIND" -> {
                val dir = VaultDav.urlToVaultPath(rawPath)
                if (!isFolder(index, dir) && fileEntry(index, dir) != null) {
                    // PROPFIND on a file: report just it.
                    val f = fileEntry(index, dir)!!
                    val body = VaultDav.propfind(index, VaultFs.parent(dir), 0)
                    writeText(out, "207 Multi-Status", "application/xml; charset=utf-8", body)
                } else {
                    val body = VaultDav.propfind(index, dir, depth.coerceIn(0, 1))
                    writeText(out, "207 Multi-Status", "application/xml; charset=utf-8", body)
                }
            }
            "GET", "HEAD" -> {
                val path = VaultDav.urlToVaultPath(rawPath)
                val entry = fileEntry(index, path)
                if (entry != null) {
                    if (method == "HEAD") {
                        writeHead(out, "200 OK", mapOf(
                            "Content-Type" to VaultDav.contentType(entry.name),
                            "Content-Length" to entry.originalSize.toString(),
                            "Accept-Ranges" to "bytes"
                        ))
                    } else if (range == null) {
                        // No range asked for, so the body can go straight down the
                        // socket a frame at a time instead of being held whole.
                        // Content-Length comes from the index, which is why the
                        // header can be written before the first byte is decrypted.
                        writeHead(out, "200 OK", mapOf(
                            "Content-Type" to VaultDav.contentType(entry.name),
                            "Content-Length" to entry.originalSize.toString(),
                            "Accept-Ranges" to "bytes"
                        ))
                        // A failure here cannot become a 500: the status line is
                        // already sent, so the client sees a short body instead.
                        runCatching { vault.restoreTo(entry.fileId, password, pool, out) }
                        out.flush()
                    } else {
                        val bytes = runCatching { vault.restore(entry.fileId, password, pool).second }.getOrNull()
                        if (bytes == null) writeText(out, "500 Internal Server Error", "text/plain", "restore failed")
                        else writeBody(out, VaultDav.contentType(entry.name), bytes, range, headOnly = false)
                    }
                } else if (isFolder(index, path)) {
                    // Our own client asks for JSON; a browser gets the HTML page.
                    val wantsJson = rawPath.substringAfter('?', "").contains(VaultDav.JSON_QUERY)
                    val type = if (wantsJson) "application/json" else "text/html; charset=utf-8"
                    if (method == "HEAD") writeHead(out, "200 OK", mapOf("Content-Type" to type))
                    else writeText(
                        out, "200 OK", type,
                        if (wantsJson) VaultDav.jsonListing(index, path) else VaultDav.htmlListing(index, path)
                    )
                } else {
                    writeText(out, "404 Not Found", "text/plain", "not found")
                }
            }
            else -> writeHead(out, "405 Method Not Allowed", mapOf("Allow" to "OPTIONS, GET, HEAD, PROPFIND"))
        }
    }

    private fun isFolder(index: VaultVolume.Index, path: String): Boolean =
        path == "/" || VaultFs.allFolders(index).contains(VaultFs.normalize(path))

    private fun fileEntry(index: VaultVolume.Index, path: String): VaultVolume.Entry? {
        val p = VaultFs.normalize(path)
        val dir = VaultFs.parent(p)
        val name = VaultFs.baseName(p)
        return index.entries.firstOrNull { VaultFs.normalize(it.path) == dir && it.name == name }
    }

    // ---- minimal HTTP writers ----

    private fun writeHead(out: OutputStream, status: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status\r\n")
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun writeText(out: OutputStream, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(out, status, mapOf("Content-Type" to contentType, "Content-Length" to bytes.size.toString()))
        out.write(bytes); out.flush()
    }

    /**
     * Send a body, honouring a Range request so clients can seek. Renderers ask
     * for ranges constantly during playback, and a server that ignores them makes
     * scrubbing impossible and forces the client to buffer the whole track.
     */
    private fun writeBody(
        out: OutputStream,
        contentType: String,
        body: ByteArray,
        rangeHeader: String?,
        headOnly: Boolean
    ) {
        val size = body.size.toLong()
        val wanted = VaultDav.parseRange(rangeHeader, size)

        if (rangeHeader != null && wanted == null) {
            writeHead(out, "416 Range Not Satisfiable", mapOf(
                "Content-Range" to "bytes */$size",
                "Content-Length" to "0",
                "Accept-Ranges" to "bytes"
            ))
            return
        }

        if (wanted == null) {
            writeHead(out, "200 OK", mapOf(
                "Content-Type" to contentType,
                "Content-Length" to size.toString(),
                "Accept-Ranges" to "bytes"
            ))
            if (!headOnly) { out.write(body); out.flush() }
            return
        }

        val start = wanted.first.toInt()
        val length = (wanted.last - wanted.first + 1).toInt()
        writeHead(out, "206 Partial Content", mapOf(
            "Content-Type" to contentType,
            "Content-Length" to length.toString(),
            "Content-Range" to "bytes ${wanted.first}-${wanted.last}/$size",
            "Accept-Ranges" to "bytes"
        ))
        if (!headOnly) { out.write(body, start, length); out.flush() }
    }

    /**
     * Serve a single file against a cast capability, with no Basic auth. An
     * unknown or expired token gets a flat 404 rather than a 401, so probing the
     * endpoint reveals nothing about whether casting is in use.
     *
     * The decrypted bytes are cached against the grant, because a renderer will
     * issue many range requests for one track and re-running the whole restore
     * per seek would be brutal on a low-power device.
     */
    private fun serveCast(out: OutputStream, castToken: String, method: String, range: String?) {
        val grant = grants.resolve(castToken)
        if (grant == null) {
            writeHead(out, "404 Not Found", mapOf("Content-Length" to "0"))
            return
        }
        val bytes = grants.cached(castToken) ?: run {
            val restored = runCatching {
                vault.restore(grant.fileId, password, poolProvider()).second
            }.getOrNull()
            if (restored != null) grants.cache(castToken, restored)
            restored
        }
        if (bytes == null) {
            writeText(out, "500 Internal Server Error", "text/plain", "restore failed")
            return
        }
        writeBody(out, grant.contentType, bytes, range, headOnly = method == "HEAD")
    }
}
