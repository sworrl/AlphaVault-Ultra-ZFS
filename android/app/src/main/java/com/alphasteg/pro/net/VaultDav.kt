package com.alphasteg.pro.net

import com.alphasteg.pro.data.VaultFs
import com.alphasteg.pro.data.VaultVolume
import java.util.Base64

/**
 * Pure request/response logic for the vault network drive, split out so it can be
 * unit-tested without opening a socket. [VaultWebServer] does the I/O and calls
 * these to build listings, WebDAV replies, and auth checks.
 *
 * The drive is read-only and served only while the vault is unlocked. Every
 * request must carry HTTP Basic credentials whose password equals the per-session
 * token, so a device on the same network that does not know the token sees only
 * 401s and never any file content.
 */
object VaultDav {

    const val USER = "vault"

    /** True if an `Authorization: Basic ...` header decodes to user:token. */
    fun authOk(authHeader: String?, token: String): Boolean {
        if (token.isEmpty()) return false
        val v = authHeader?.trim() ?: return false
        if (!v.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            String(Base64.getDecoder().decode(v.substring(6).trim()), Charsets.UTF_8)
        }.getOrNull() ?: return false
        val i = decoded.indexOf(':')
        if (i < 0) return false
        return decoded.substring(0, i) == USER && decoded.substring(i + 1) == token
    }

    /** Map a URL path to a normalized vault path (folder or "/file/<id>" leaf resolves to a folder path here). */
    fun urlToVaultPath(rawPath: String): String {
        val decoded = rawPath.substringBefore('?')
            .split('/').joinToString("/") { java.net.URLDecoder.decode(it, "UTF-8") }
        return VaultFs.normalize(decoded)
    }

    fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "m4a", "aac" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "txt", "md", "log" -> "text/plain; charset=utf-8"
        "csv" -> "text/csv; charset=utf-8"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun hrefEncode(path: String): String =
        path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /**
     * A WebDAV 207 Multi-Status body for [dir] and, when [depth] >= 1, its direct
     * children, so a mounted drive shows folders and files with sizes.
     */
    fun propfind(index: VaultVolume.Index, dir: String, depth: Int): String {
        val listing = VaultFs.listing(index, dir)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\n")
        sb.append(collectionResponse(dir))
        if (depth >= 1) {
            for (f in listing.folders) sb.append(collectionResponse(f))
            for (file in listing.files) sb.append(fileResponse(VaultFs.join(dir, file.name), file.originalSize))
        }
        sb.append("</D:multistatus>\n")
        return sb.toString()
    }

    private fun collectionResponse(path: String): String {
        val href = if (path == "/") "/" else hrefEncode(path) + "/"
        val name = if (path == "/") "Vault" else VaultFs.baseName(path)
        return """
          <D:response>
            <D:href>${xmlEscape(href)}</D:href>
            <D:propstat>
              <D:prop>
                <D:displayname>${xmlEscape(name)}</D:displayname>
                <D:resourcetype><D:collection/></D:resourcetype>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        """.trimIndent() + "\n"
    }

    private fun fileResponse(path: String, size: Long): String {
        val name = VaultFs.baseName(path)
        return """
          <D:response>
            <D:href>${xmlEscape(hrefEncode(path))}</D:href>
            <D:propstat>
              <D:prop>
                <D:displayname>${xmlEscape(name)}</D:displayname>
                <D:resourcetype/>
                <D:getcontentlength>$size</D:getcontentlength>
                <D:getcontenttype>${xmlEscape(contentType(name))}</D:getcontenttype>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        """.trimIndent() + "\n"
    }

    /** A browsable HTML listing for a folder, so a plain browser works too. */
    fun htmlListing(index: VaultVolume.Index, dir: String): String {
        val listing = VaultFs.listing(index, dir)
        val sb = StringBuilder()
        sb.append("<!doctype html><meta charset=utf-8><title>AlphaVault</title>")
        sb.append("<body style=\"font-family:system-ui;background:#0b1120;color:#eaf2ff;padding:16px\">")
        sb.append("<h2>🔒 ${xmlEscape(if (dir == "/") "Vault" else dir)}</h2><ul>")
        if (dir != "/") sb.append("<li><a href=\"${hrefEncode(VaultFs.parent(dir))}/\">../</a></li>")
        for (f in listing.folders)
            sb.append("<li>📁 <a href=\"${hrefEncode(f)}/\">${xmlEscape(VaultFs.baseName(f))}/</a></li>")
        for (file in listing.files)
            sb.append("<li>🔐 <a href=\"${hrefEncode(VaultFs.join(dir, file.name))}\">${xmlEscape(file.name)}</a> (${file.originalSize} bytes)</li>")
        sb.append("</ul></body>")
        return sb.toString()
    }
}
