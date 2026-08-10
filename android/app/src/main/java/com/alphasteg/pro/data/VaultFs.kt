package com.alphasteg.pro.data

import com.alphasteg.pro.data.VaultVolume.Entry
import com.alphasteg.pro.data.VaultVolume.Index

/**
 * The vault's directory tree as pure functions over a [VaultVolume.Index].
 *
 * Every operation returns a new index and never touches a data chunk, so moving a
 * file or renaming a folder is only a change to the directory listing, the same
 * as moving a file inside an already-mounted LUKS volume. Nothing is encrypted or
 * re-chunked here; [VaultSession] decides when to flush the changed index back to
 * the carriers (on lock).
 *
 * Paths are POSIX-like and always absolute: "/" is the root, "/Photos/2026" is a
 * nested folder. Names are the file names; a file's folder is [Entry.path].
 */
object VaultFs {

    data class DirListing(
        val path: String,
        val folders: List<String>,   // immediate subfolder paths, sorted
        val files: List<Entry>       // files whose folder == path
    )

    /** Canonical absolute form: leading slash, no trailing slash (except root), no empty or `.`/`..` segments. */
    fun normalize(raw: String): String {
        val stack = ArrayDeque<String>()
        for (seg in raw.split('/')) {
            val s = seg.trim()
            when {
                s.isEmpty() || s == "." -> {}
                s == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(s)
            }
        }
        return if (stack.isEmpty()) "/" else "/" + stack.joinToString("/")
    }

    fun parent(path: String): String {
        val n = normalize(path)
        if (n == "/") return "/"
        val i = n.lastIndexOf('/')
        return if (i <= 0) "/" else n.substring(0, i)
    }

    /** The last path segment ("" for the root). */
    fun baseName(path: String): String {
        val n = normalize(path)
        return if (n == "/") "" else n.substringAfterLast('/')
    }

    fun join(dir: String, name: String): String = normalize("$dir/$name")

    /** Every path from "/" down to and including [path]. */
    fun ancestors(path: String): List<String> {
        val n = normalize(path)
        if (n == "/") return listOf("/")
        val out = ArrayList<String>()
        out.add("/")
        var cur = ""
        for (seg in n.removePrefix("/").split('/')) {
            cur += "/$seg"
            out.add(cur)
        }
        return out
    }

    /** All folders that exist: explicitly created ones plus every ancestor implied by a file's path. */
    fun allFolders(index: Index): Set<String> {
        val set = sortedSetOf("/")
        for (f in index.folders) set.addAll(ancestors(f))
        for (e in index.entries) set.addAll(ancestors(e.path))
        return set
    }

    fun childFolders(index: Index, path: String): List<String> {
        val p = normalize(path)
        return allFolders(index).filter { it != "/" && parent(it) == p }.sorted()
    }

    fun filesIn(index: Index, path: String): List<Entry> {
        val p = normalize(path)
        return index.entries.filter { normalize(it.path) == p }
    }

    fun listing(index: Index, path: String): DirListing {
        val p = normalize(path)
        return DirListing(p, childFolders(index, p), filesIn(index, p))
    }

    /** True if any file or subfolder lives at [path] or anywhere beneath it. */
    fun subtreeHasContent(index: Index, path: String): Boolean {
        val p = normalize(path)
        val prefix = if (p == "/") "/" else "$p/"
        val fileUnder = index.entries.any {
            val ep = normalize(it.path); ep == p || ep.startsWith(prefix)
        }
        val folderUnder = allFolders(index).any { it != p && it.startsWith(prefix) }
        return fileUnder || folderUnder
    }

    private fun withFolders(index: Index, vararg ensure: String): List<String> {
        val set = LinkedHashSet(index.folders.map { normalize(it) })
        for (path in ensure) set.addAll(ancestors(path).filter { it != "/" })
        return set.toList()
    }

    fun mkdir(index: Index, path: String): Index {
        val p = normalize(path)
        if (p == "/") return index
        return index.copy(folders = withFolders(index, p))
    }

    /** Remove an empty folder. Throws if anything still lives under it. */
    fun rmdir(index: Index, path: String): Index {
        val p = normalize(path)
        require(p != "/") { "Cannot remove the vault root." }
        require(!subtreeHasContent(index, p)) { "Folder is not empty." }
        return index.copy(folders = index.folders.map { normalize(it) }.filter { it != p })
    }

    /** Move a file into [toDir]; the destination folder is created if needed. */
    fun move(index: Index, fileId: String, toDir: String): Index {
        val dir = normalize(toDir)
        val entries = index.entries.map { if (it.fileId == fileId) it.copy(path = dir) else it }
        return index.copy(entries = entries, folders = withFolders(index, dir))
    }

    fun rename(index: Index, fileId: String, newName: String): Index {
        val clean = newName.trim().ifEmpty { return index }
        return index.copy(entries = index.entries.map { if (it.fileId == fileId) it.copy(name = clean) else it })
    }

    /** Move or rename a folder subtree; files and subfolders are reparented (no data touched). */
    fun moveFolder(index: Index, from: String, to: String): Index {
        val src = normalize(from)
        val dst = normalize(to)
        require(src != "/") { "Cannot move the vault root." }
        require(dst != src && !dst.startsWith("$src/")) { "Cannot move a folder into itself." }

        fun remap(path: String): String {
            val n = normalize(path)
            return when {
                n == src -> dst
                n.startsWith("$src/") -> dst + n.substring(src.length)
                else -> n
            }
        }

        val entries = index.entries.map { it.copy(path = remap(it.path)) }
        val folders = LinkedHashSet<String>()
        for (f in index.folders) folders.add(remap(f))
        folders.addAll(ancestors(dst).filter { it != "/" })
        return index.copy(entries = entries, folders = folders.filter { it != "/" }.toList())
    }
}
