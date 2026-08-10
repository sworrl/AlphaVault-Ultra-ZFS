package com.alphasteg.pro.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub repo's latest release for a newer APK and installs it after
 * the user accepts. The check hits the public releases API; the install uses
 * Android's PackageInstaller (which shows the system confirm dialog), so no APK
 * is ever installed silently.
 */
object UpdateManager {

    private const val LATEST_RELEASE = "https://api.github.com/repos/sworrl/AlphaVault-Ultra-ZFS/releases/latest"

    data class Release(val version: String, val notes: String, val apkUrl: String)

    /** Returns a Release only if it is newer than [currentVersion]; else null. */
    fun checkLatest(currentVersion: String): Release? {
        val json = httpGet(LATEST_RELEASE) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = obj.optString("tag_name").ifBlank { obj.optString("name") }
        if (tag.isBlank() || !isNewer(tag, currentVersion)) return null

        val assets = obj.optJSONArray("assets") ?: return null
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                apk = a.optString("browser_download_url"); break
            }
        }
        val url = apk ?: return null
        return Release(tag, obj.optString("body").take(2000), url)
    }

    /** Compare version strings by their numeric components (e.g. v1.0.2 > 1.0.0-Ultra-ZFS). */
    fun isNewer(latest: String, current: String): Boolean {
        fun nums(s: String) = Regex("""\d+""").findAll(s).map { it.value.toInt() }.toList()
        val a = nums(latest); val b = nums(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Download the APK bytes, reporting progress (0..100), or null on failure. */
    fun download(apkUrl: String, onProgress: (Int) -> Unit): ByteArray? = runCatching {
        val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000; readTimeout = 30000
        }
        conn.inputStream.use { input ->
            val total = conn.contentLength.toLong()
            val buf = ByteArray(1 shl 16)
            val out = java.io.ByteArrayOutputStream()
            var read = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n); read += n
                if (total > 0) onProgress((read * 100 / total).toInt().coerceIn(0, 100))
            }
            out.toByteArray()
        }
    }.getOrNull()

    /** Hand the APK to PackageInstaller; the system shows the confirm dialog. */
    fun install(context: Context, apk: ByteArray) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("alphavault_update", 0, apk.size.toLong()).use { out ->
                out.write(apk); session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
    }

    private fun httpGet(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AlphaVault")
            connectTimeout = 15000; readTimeout = 15000
        }
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()
}
