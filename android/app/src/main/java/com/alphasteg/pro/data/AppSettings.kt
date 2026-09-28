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
     * How new files are hidden in the FLAC carriers. METADATA (default) leaves the
     * audio, tags and art byte-identical but the blocks are visible to any FLAC
     * parser. LSB hides the data in the audio itself, but its re-encode currently
     * writes 16-bit PCM and drops every metadata block but STREAMINFO, so a 24-bit
     * carrier loses resolution and every carrier loses its tags and art. It stays
     * opt-in until that re-encode is lossless.
     */
    var carrierMethod: CarrierMethod
        get() = CarrierMethod.fromKey(prefs.getString(KEY_CARRIER_METHOD, CarrierMethod.METADATA.key))
        set(value) { prefs.edit().putString(KEY_CARRIER_METHOD, value.key).apply() }

    /**
     * Set once this install has confirmed no vault files remain in the legacy
     * AVMAX768 envelope. Current builds never write it, so the check never repeats.
     */
    var legacyFormatChecked: Boolean
        get() = prefs.getBoolean(KEY_LEGACY_CHECKED, false)
        set(value) { prefs.edit().putBoolean(KEY_LEGACY_CHECKED, value).apply() }

    companion object {
        private const val KEY_LEGACY_CHECKED = "legacy_format_checked"
        private const val KEY_SCRAMBLE_PER_PRESS = "scramble_per_press"
        private const val KEY_CARRIER_METHOD = "carrier_method"
    }
}

/** The two ways AlphaVault can hide data in a FLAC. */
enum class CarrierMethod(val key: String, val label: String, val hidden: Boolean) {
    /** Hidden in the audio sample LSBs, keyed, no fingerprint. Presence concealed. */
    LSB("lsb", "Hidden in audio (16-bit re-encode, drops tags and art)", hidden = true),

    /** Stored in FLAC APPLICATION metadata blocks. Audio untouched, but the blocks
     *  are visible to any FLAC parser, so presence is detectable. */
    METADATA("metadata", "Metadata blocks (less secure, visible)", hidden = false);

    companion object {
        fun fromKey(k: String?): CarrierMethod = entries.firstOrNull { it.key == k } ?: METADATA
    }
}
