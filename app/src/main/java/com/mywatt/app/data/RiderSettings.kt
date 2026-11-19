package com.mywatt.app.data

import android.content.Context
import androidx.core.content.edit
import com.mywatt.app.engine.CueSettings
import com.mywatt.app.engine.RecoverySetting

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

    /** True once the rider has saved an FTP; until then [ftpWatts] is only the default. */
    val isFtpSet: Boolean get() = prefs.contains(KEY_FTP)

    /** How demanding the heart-rate gate is. Defaults to [RecoverySetting.DEFAULT] (Building). */
    var recoverySetting: RecoverySetting
        get() = RecoverySetting.fromName(prefs.getString(KEY_RECOVERY_SETTING, null))
        set(value) = prefs.edit { putString(KEY_RECOVERY_SETTING, value.name) }

    /** Audio cues during a session: master switch (default on), per-tier switches, beep-only. */
    var cueSettings: CueSettings
        get() = CueSettings(
            enabled = prefs.getBoolean(KEY_CUES_ON, true),
            shortTier = prefs.getBoolean(KEY_CUES_SHORT, true),
            midTier = prefs.getBoolean(KEY_CUES_MID, true),
            longTier = prefs.getBoolean(KEY_CUES_LONG, true),
            beepOnly = prefs.getBoolean(KEY_CUES_BEEP_ONLY, false),
        )
        set(value) = prefs.edit {
            putBoolean(KEY_CUES_ON, value.enabled)
            putBoolean(KEY_CUES_SHORT, value.shortTier)
            putBoolean(KEY_CUES_MID, value.midTier)
            putBoolean(KEY_CUES_LONG, value.longTier)
            putBoolean(KEY_CUES_BEEP_ONLY, value.beepOnly)
        }

    /** Bluetooth address and name of the trainer ridden last, for reconnecting on launch. */
    var trainerAddress: String?
        get() = prefs.getString(KEY_TRAINER_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_TRAINER_ADDRESS, value) }
    var trainerName: String?
        get() = prefs.getString(KEY_TRAINER_NAME, null)
        set(value) = prefs.edit { putString(KEY_TRAINER_NAME, value) }

    /** Bluetooth address and name of the heart-rate strap worn last, for reconnecting on launch. */
    var strapAddress: String?
        get() = prefs.getString(KEY_STRAP_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_STRAP_ADDRESS, value) }
    var strapName: String?
        get() = prefs.getString(KEY_STRAP_NAME, null)
        set(value) = prefs.edit { putString(KEY_STRAP_NAME, value) }

    companion object {
        const val DEFAULT_FTP = 200
        const val MIN_FTP = 50
        const val MAX_FTP = 700

        const val DEFAULT_SMOOTHING_SECONDS = 2
        const val MIN_SMOOTHING_SECONDS = 1
        const val MAX_SMOOTHING_SECONDS = 5

        private const val PREFS_NAME = "mywatt_settings"
        private const val KEY_FTP = "ftp_watts"
        private const val KEY_SMOOTHING_SECONDS = "smoothing_seconds"
        private const val KEY_RECOVERY_SETTING = "recovery_setting"
        private const val KEY_TRAINER_ADDRESS = "trainer_address"
        private const val KEY_TRAINER_NAME = "trainer_name"
        private const val KEY_STRAP_ADDRESS = "strap_address"
        private const val KEY_STRAP_NAME = "strap_name"
        private const val KEY_CUES_ON = "cues_on"
        private const val KEY_CUES_SHORT = "cues_short"
        private const val KEY_CUES_MID = "cues_mid"
        private const val KEY_CUES_LONG = "cues_long"
        private const val KEY_CUES_BEEP_ONLY = "cues_beep_only"
    }
}
