package com.mywatt.app.data

import android.content.Context
import androidx.core.content.edit

/**
 * Rider preferences backed by SharedPreferences. FTP drives every zone boundary and the
 * full-scale reading of the rev counter; the smoothing window sets how many seconds of
 * power the readouts average over.
 */
class RiderSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Functional threshold power in watts. Defaults to [DEFAULT_FTP]. */
    var ftpWatts: Int
        get() = prefs.getInt(KEY_FTP, DEFAULT_FTP).coerceIn(MIN_FTP, MAX_FTP)
        set(value) = prefs.edit { putInt(KEY_FTP, value.coerceIn(MIN_FTP, MAX_FTP)) }

    /** Length of the rolling power average in seconds. Defaults to [DEFAULT_SMOOTHING_SECONDS]. */
    var smoothingSeconds: Int
        get() = prefs.getInt(KEY_SMOOTHING_SECONDS, DEFAULT_SMOOTHING_SECONDS)
            .coerceIn(MIN_SMOOTHING_SECONDS, MAX_SMOOTHING_SECONDS)
        set(value) = prefs.edit {
            putInt(
                KEY_SMOOTHING_SECONDS,
                value.coerceIn(MIN_SMOOTHING_SECONDS, MAX_SMOOTHING_SECONDS),
            )
        }

    companion object {
        const val DEFAULT_FTP = 220
        const val MIN_FTP = 50
        const val MAX_FTP = 700

        const val DEFAULT_SMOOTHING_SECONDS = 2
        const val MIN_SMOOTHING_SECONDS = 1
        const val MAX_SMOOTHING_SECONDS = 5

        private const val PREFS_NAME = "mywatt_settings"
        private const val KEY_FTP = "ftp_watts"
        private const val KEY_SMOOTHING_SECONDS = "smoothing_seconds"
    }
}
