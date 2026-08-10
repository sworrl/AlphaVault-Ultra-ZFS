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

    companion object {
        private const val KEY_SCRAMBLE_PER_PRESS = "scramble_per_press"
    }
}
