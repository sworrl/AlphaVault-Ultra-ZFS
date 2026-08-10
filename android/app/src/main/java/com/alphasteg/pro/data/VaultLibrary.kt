package com.alphasteg.pro.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent record of the FLAC tracks AlphaVault knows about.
 *
 * A track is identified by its absolute path. sync() compares a fresh scan of
 * the device against the stored set and reports what is new and what has gone
 * missing, then persists the new set. Backed by SharedPreferences as a small
 * JSON array, so there is no database dependency.
 */
class VaultLibrary(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class SyncResult(
        val tracks: List<FlacTrack>,   // full current library, sorted by name
        val added: List<FlacTrack>,    // present now, absent at last sync
        val removed: List<FlacTrack>,  // in the database, absent from the device now
        val totalBytes: Long
    )

    fun load(): Map<String, FlacTrack> {
        val raw = prefs.getString(KEY_TRACKS, null) ?: return emptyMap()
        val out = LinkedHashMap<String, FlacTrack>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = FlacTrack(
                    path = o.getString("path"),
                    name = o.getString("name"),
                    size = o.getLong("size")
                )
                out[t.path] = t
            }
        }
        return out
    }

    /** Reconcile a fresh device scan against the stored library and persist the result. */
    fun sync(scanned: List<FlacTrack>): SyncResult {
        val known = load()
        val scannedByPath = scanned.associateBy { it.path }

        val added = scanned.filter { it.path !in known }
        val removed = known.values.filter { it.path !in scannedByPath }

        // The new library is exactly what is on the device now.
        val current = scanned.sortedBy { it.name.lowercase() }
        persist(current)

        return SyncResult(
            tracks = current,
            added = added,
            removed = removed,
            totalBytes = current.sumOf { it.size }
        )
    }

    private fun persist(tracks: List<FlacTrack>) {
        val arr = JSONArray()
        for (t in tracks) {
            arr.put(JSONObject().apply {
                put("path", t.path)
                put("name", t.name)
                put("size", t.size)
            })
        }
        prefs.edit().putString(KEY_TRACKS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "alphavault_library"
        private const val KEY_TRACKS = "flac_tracks"
    }
}
