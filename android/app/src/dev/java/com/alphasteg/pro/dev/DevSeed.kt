package com.alphasteg.pro.dev

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.alphasteg.pro.data.VaultFs
import com.alphasteg.pro.data.VaultVolume
import com.alphasteg.pro.security.SecurityManager
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Fake data for the dev flavor only, so the emulator can produce screenshots and
 * media without any real vault. None of this is compiled into a prod build (the
 * prod source set has a no-op stub of the same name).
 */
object DevSeed {

    // Fixed throwaway codes so a dev build unlocks without onboarding. Both are
    // valid Hex++ codes (0-9, a-f, symbols), master distinct from duress.
    private const val DEV_CODE = "c0ffee42"
    private const val DEV_DURESS = "deadbeef"

    /** Set the dev credentials if needed and return the code to enter with. */
    fun provisionCredentials(security: SecurityManager): String {
        if (!security.isVaultSetup()) security.setupCredentials(DEV_CODE, DEV_DURESS)
        return DEV_CODE
    }

    /**
     * On a dev build with carriers present and an empty vault, vault a few sample
     * files and sort them into folders, so the vault and folder screens have
     * content to show. Runs once; safe to call repeatedly.
     */
    fun maybeSeed(
        context: Context,
        vault: VaultVolume,
        pool: List<File>,
        password: String,
        onDone: () -> Unit
    ) {
        if (pool.isEmpty()) return
        if (vault.list(pool, password).isNotEmpty()) return
        Thread {
            runCatching {
                val now = System.currentTimeMillis()
                vault.vault("Passport.png", solidPng(1200, 760, Color.rgb(46, 204, 113)), password, pool, now)
                vault.vault("Contract.txt", SAMPLE_TEXT.toByteArray(Charsets.UTF_8), password, pool, now + 1)
                vault.vault("Budget.csv", SAMPLE_CSV.toByteArray(Charsets.UTF_8), password, pool, now + 2)
                vault.vault("Skyline.jpg", solidPng(1000, 1400, Color.rgb(0, 242, 254)), password, pool, now + 3)

                // Organize into folders. Metadata only: no re-encryption, no chunk I/O.
                var idx = vault.loadIndex(pool, password)
                idx = VaultFs.mkdir(idx, "/Documents")
                idx = VaultFs.mkdir(idx, "/Photos")
                idx = VaultFs.mkdir(idx, "/Photos/Trip")
                for (e in idx.entries) {
                    val dest = when (e.name.substringAfterLast('.', "").lowercase()) {
                        "txt", "csv", "pdf" -> "/Documents"
                        "png" -> "/Photos"
                        "jpg", "jpeg" -> "/Photos/Trip"
                        else -> "/"
                    }
                    idx = VaultFs.move(idx, e.fileId, dest)
                }
                idx = idx.copy(entries = idx.entries.map {
                    if (it.name == "Passport.png") it.copy(colorLabel = Color.rgb(255, 93, 93)) else it
                })
                vault.commitIndex(idx.copy(generation = idx.generation + 1), pool, password)
            }
            onDone()
        }.start()
    }

    private fun solidPng(w: Int, h: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(60, 255, 255, 255)
        }
        // A little structure so it is not a flat block.
        for (i in 0 until 6) c.drawCircle(w * (0.15f + i * 0.13f), h * 0.5f, w * 0.08f, paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private const val SAMPLE_TEXT =
        "CONFIDENTIAL\n\nThis is sample content stored inside the AlphaVault dev build.\n" +
        "It lives only on the emulator and contains no real information.\n"

    private const val SAMPLE_CSV = "item,amount\nrent,1200\ngroceries,420\ntransit,95\nsavings,600\n"
}
