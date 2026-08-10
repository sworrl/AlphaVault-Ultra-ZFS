package com.alphasteg.pro

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import java.io.FileOutputStream

/**
 * Serves decrypted vaulted bytes to the system's default viewers (image, video,
 * PDF, and so on) WITHOUT writing plaintext to disk. Bytes are held in memory
 * and streamed to the reading app through a pipe. Each entry is single-use and
 * dropped once read.
 *
 * This lets us hand large media to the OS's real players instead of decoding
 * everything ourselves, while keeping the plaintext off storage.
 */
class VaultContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        val name = store[uri.lastPathSegment]?.first ?: return "application/octet-stream"
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val entry = store[uri.lastPathSegment] ?: throw java.io.FileNotFoundException("No such vault item")
        val bytes = entry.second
        val pipe = ParcelFileDescriptor.createPipe()
        val out = pipe[1]
        Thread {
            try {
                FileOutputStream(out.fileDescriptor).use { it.write(bytes) }
            } catch (_: Exception) {
            } finally {
                runCatching { out.close() }
                store.remove(uri.lastPathSegment) // single use
            }
        }.start()
        return pipe[0]
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0

    companion object {
        private const val AUTHORITY = "com.alphasteg.pro.vault"
        private val store = java.util.concurrent.ConcurrentHashMap<String, Pair<String, ByteArray>>()
        private var counter = 0L

        /** Register bytes under a fresh content URI and return it. */
        @Synchronized
        fun publish(name: String, bytes: ByteArray): Uri {
            val id = (counter++).toString(36) + "_" + name.hashCode().toUInt().toString(36)
            store[id] = name to bytes
            return Uri.parse("content://$AUTHORITY/$id")
        }
    }
}
