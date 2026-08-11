package com.alphasteg.pro.dev

import android.content.Context
import com.alphasteg.pro.data.VaultVolume
import com.alphasteg.pro.security.SecurityManager
import java.io.File

/**
 * Production stub. The real seeding lives only in the `dev` flavor source set, so
 * no fake data, dev credentials, or auto-unlock ever ship in a prod build.
 */
object DevSeed {
    /** No dev unlock in prod. */
    fun provisionCredentials(security: SecurityManager): String = ""

    /** No seeding in prod. */
    fun maybeSeed(
        context: Context,
        vault: VaultVolume,
        pool: List<File>,
        password: String,
        onDone: () -> Unit
    ) { /* no-op */ }
}
