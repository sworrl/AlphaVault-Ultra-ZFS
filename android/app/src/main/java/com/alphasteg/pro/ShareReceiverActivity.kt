package com.alphasteg.pro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatActivity

/**
 * Share target for "Move to Vault" / "Copy to Vault". Appears in the system
 * share sheet only when the app is not disguised (the alias is disabled with the
 * calculator cover). It reads the shared bytes while it still holds the grant,
 * stashes them, then routes through the normal lock screen so the vault is only
 * written after the user unlocks with their code.
 */
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val items = extractShared()
        if (items.isEmpty()) {
            finish()
            return
        }
        PendingShare.items = items
        startActivity(
            Intent(this, LockScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun extractShared(): List<PendingShare.Item> {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else -> emptyList()
        }
        return uris.mapNotNull { uri ->
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@mapNotNull null
                PendingShare.Item(uri, displayNameOf(uri), bytes)
            }.getOrNull()
        }
    }

    private fun displayNameOf(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { return it }
        }
        return uri.lastPathSegment ?: "shared_file"
    }
}

/** In-memory handoff of shared items from the share target to MainActivity, after unlock. */
object PendingShare {
    data class Item(val uri: Uri, val name: String, val bytes: ByteArray)
    @Volatile var items: List<Item> = emptyList()
}
