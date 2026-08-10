package com.alphasteg.pro.data

import java.io.File

/**
 * A mounted vault: the decrypted index held in memory for the length of an unlock,
 * like an open LUKS mapping over the RAID array.
 *
 * Folder and move operations run against the in-memory index and return
 * immediately; no encryption or chunk I/O happens per action. [flush] writes the
 * changed index back to the carriers once, and the UI calls it when the vault
 * locks or the app is backgrounded. Adding or removing a file still goes through
 * [VaultVolume] directly, because those touch data chunks; after such a call, use
 * [reload] to pull the new index into the session.
 */
class VaultSession(
    private val vol: VaultVolume,
    val pool: List<File>,
    private val password: String
) {
    var index: VaultVolume.Index = vol.loadIndex(pool, password)
        private set

    /** Current working folder for relative operations and listing. */
    var cwd: String = "/"
        private set

    private var dirty = false
    fun isDirty(): Boolean = dirty

    private fun resolve(path: String): String =
        if (path.startsWith("/")) VaultFs.normalize(path) else VaultFs.join(cwd, path)

    fun cd(path: String) { cwd = resolve(path) }
    fun up() { cwd = VaultFs.parent(cwd) }

    fun listing(path: String = cwd): VaultFs.DirListing = VaultFs.listing(index, resolve(path))

    private inline fun mutate(f: (VaultVolume.Index) -> VaultVolume.Index) {
        index = f(index)
        dirty = true
    }

    fun mkdir(path: String) = mutate { VaultFs.mkdir(it, resolve(path)) }
    fun rmdir(path: String) = mutate { VaultFs.rmdir(it, resolve(path)) }
    fun move(fileId: String, toDir: String) = mutate { VaultFs.move(it, fileId, resolve(toDir)) }
    fun rename(fileId: String, newName: String) = mutate { VaultFs.rename(it, fileId, newName) }
    fun moveFolder(from: String, to: String) = mutate { VaultFs.moveFolder(it, resolve(from), resolve(to)) }

    /** Re-read the index after a data operation (vault/delete) done through [VaultVolume]. */
    fun reload() {
        index = vol.loadIndex(pool, password)
        dirty = false
    }

    /**
     * Persist pending folder/move edits to the carriers, bumping the generation.
     * A no-op when nothing changed, so calling it on every lock is cheap.
     */
    @JvmOverloads
    fun flush(progress: VaultVolume.Progress = VaultVolume.Progress { _, _, _ -> }) {
        if (!dirty) return
        index = index.copy(generation = index.generation + 1)
        vol.commitIndex(index, pool, password, progress)
        dirty = false
    }
}
