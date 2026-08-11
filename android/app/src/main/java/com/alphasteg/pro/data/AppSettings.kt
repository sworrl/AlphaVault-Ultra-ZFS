package com.alphasteg.pro.data

import android.content.Context

/** User-adjustable options, stored in SharedPreferences. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("alphavault_settings", Context.MODE_PRIVATE)

    /**
     * When true, the lock keypad reshuffles after every keypress (extra secure,
     * slower to type). When false (default), it shuffles once each time the
     * keypad is shown.
     */
    var scramblePerPress: Boolean
        get() = prefs.getBoolean(KEY_SCRAMBLE_PER_PRESS, false)
        set(value) { prefs.edit().putBoolean(KEY_SCRAMBLE_PER_PRESS, value).apply() }

    /**
     * How new files are hidden in the FLAC carriers. LSB (default) hides the data
     * in the audio itself so a metadata scan finds nothing; METADATA is faster and
     * leaves the audio byte-identical but the blocks are visible to any FLAC
     * parser, so it is the less private choice.
     */
    var carrierMethod: CarrierMethod
        get() = CarrierMethod.fromKey(prefs.getString(KEY_CARRIER_METHOD, CarrierMethod.LSB.key))
        set(value) { prefs.edit().putString(KEY_CARRIER_METHOD, value.key).apply() }

    companion object {
        private const val KEY_SCRAMBLE_PER_PRESS = "scramble_per_press"
        private const val KEY_CARRIER_METHOD = "carrier_method"
    }
}

/** The two ways AlphaVault can hide data in a FLAC. */
enum class CarrierMethod(val key: String, val label: String, val hidden: Boolean) {
    /** Hidden in the audio sample LSBs, keyed, no fingerprint. Presence concealed. */
    LSB("lsb", "Hidden in audio (recommended)", hidden = true),

    /** Stored in FLAC APPLICATION metadata blocks. Audio untouched, but the blocks
     *  are visible to any FLAC parser, so presence is detectable. */
    METADATA("metadata", "Metadata blocks (less secure — visible)", hidden = false);

    companion object {
        fun fromKey(k: String?): CarrierMethod = entries.firstOrNull { it.key == k } ?: LSB
    }
}
