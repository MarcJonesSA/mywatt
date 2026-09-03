package com.mywatt.app.data

import android.content.Context
import androidx.core.content.edit

/**
 * Rider preferences backed by SharedPreferences. FTP drives every zone boundary and the
 * full-scale reading of the rev counter, so it is the one value worth persisting.
 */
class RiderSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Functional threshold power in watts. Defaults to [DEFAULT_FTP]. */
    var ftpWatts: Int
        get() = prefs.getInt(KEY_FTP, DEFAULT_FTP).coerceIn(MIN_FTP, MAX_FTP)
        set(value) = prefs.edit { putInt(KEY_FTP, value.coerceIn(MIN_FTP, MAX_FTP)) }

    companion object {
        const val DEFAULT_FTP = 220
        const val MIN_FTP = 50
        const val MAX_FTP = 700

        private const val PREFS_NAME = "mywatt_settings"
        private const val KEY_FTP = "ftp_watts"
    }
}
