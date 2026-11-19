package com.mywatt.app.data

import android.content.Context
import androidx.core.content.edit

/** Where the rider is starting from, set by the first-launch questions. Nothing acts on it yet. */
enum class EntryProfile(val label: String) {
    BEGINNER("New to riding"),
    LONG_BREAK("Back after a long break"),
    SHORT_BREAK("Back after a short break"),
    OTHER_SPORT_FIT("Fit from another sport"),
    PLATEAU("Riding, new to intervals"),
    TRAINED("Trained with intervals"),
    ;

    companion object {
        fun fromName(name: String?): EntryProfile? = entries.firstOrNull { it.name == name }

        /**
         * The profile from the first-launch answers. Riding now: intervals before decides
         * TRAINED or PLATEAU. Not riding: how long since decides, and [TimeOff.NEVER] is
         * BEGINNER whatever the second answer.
         */
        fun classify(ridingNow: Boolean, timeOff: TimeOff?, intervalsBefore: Boolean): EntryProfile = when {
            ridingNow -> if (intervalsBefore) TRAINED else PLATEAU
            timeOff == TimeOff.UNDER_2_MONTHS -> SHORT_BREAK
            timeOff == TimeOff.MONTHS_2_TO_12 || timeOff == TimeOff.OVER_A_YEAR -> LONG_BREAK
            else -> BEGINNER
        }
    }
}

/** Q1 follow-up for a rider who is riding regularly. */
enum class WeeklyHours(val label: String) {
    UNDER_3("Under 3 hours"),
    H3_TO_5("3–5 hours"),
    H5_TO_8("5–8 hours"),
    OVER_8("8+ hours"),
}

/** Q1 follow-up for a rider who is not riding regularly. */
enum class TimeOff(val label: String) {
    UNDER_2_MONTHS("Under 2 months"),
    MONTHS_2_TO_12("2–12 months"),
    OVER_A_YEAR("Over a year"),
    NEVER("Never really"),
}

enum class Sex(val label: String) { FEMALE("Female"), MALE("Male"), OTHER("Other") }

/**
 * The rider's profile, backed by SharedPreferences. FTP is not stored here: it lives in
 * [RiderSettings] and the profile screen edits it there. Unset values are null; max HR in
 * particular is never estimated from age.
 */
class RiderProfile(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var name: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit { putString(KEY_NAME, value?.takeIf { it.isNotBlank() }) }

    var sex: Sex?
        get() = Sex.entries.firstOrNull { it.name == prefs.getString(KEY_SEX, null) }
        set(value) = prefs.edit { putString(KEY_SEX, value?.name) }

    /** Date of birth as yyyy-MM-dd. */
    var dateOfBirth: String?
        get() = prefs.getString(KEY_DOB, null)
        set(value) = prefs.edit { putString(KEY_DOB, value) }

    var weightKg: Double?
        get() = prefs.getFloat(KEY_WEIGHT, -1f).takeIf { it > 0 }?.toDouble()
        set(value) = prefs.edit { if (value == null) remove(KEY_WEIGHT) else putFloat(KEY_WEIGHT, value.toFloat()) }

    var heightCm: Int? by nullableInt(KEY_HEIGHT)
    var maxHr: Int? by nullableInt(KEY_MAX_HR)
    var restingHr: Int? by nullableInt(KEY_RESTING_HR)

    var entryProfile: EntryProfile?
        get() = EntryProfile.fromName(prefs.getString(KEY_ENTRY, null))
        set(value) = prefs.edit { putString(KEY_ENTRY, value?.name) }

    /** The raw first-launch answers, kept alongside the profile they gave. */
    var ridingNow: Boolean? by nullableBoolean(KEY_RIDING_NOW)
    var weeklyHours: WeeklyHours?
        get() = WeeklyHours.entries.firstOrNull { it.name == prefs.getString(KEY_HOURS, null) }
        set(value) = prefs.edit { putString(KEY_HOURS, value?.name) }
    var timeOff: TimeOff?
        get() = TimeOff.entries.firstOrNull { it.name == prefs.getString(KEY_TIME_OFF, null) }
        set(value) = prefs.edit { putString(KEY_TIME_OFF, value?.name) }
    var intervalsBefore: Boolean? by nullableBoolean(KEY_INTERVALS)

    /** True once the first-launch questions have been answered or skipped. */
    var entryQuestionsDone: Boolean
        get() = prefs.getBoolean(KEY_ENTRY_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_ENTRY_DONE, value) }

    private fun nullableInt(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, Int?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int? =
            if (prefs.contains(key)) prefs.getInt(key, 0) else null
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int?) =
            prefs.edit { if (value == null) remove(key) else putInt(key, value) }
    }

    private fun nullableBoolean(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Boolean? =
            if (prefs.contains(key)) prefs.getBoolean(key, false) else null
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean?) =
            prefs.edit { if (value == null) remove(key) else putBoolean(key, value) }
    }

    companion object {
        private const val PREFS_NAME = "mywatt_profile"
        private const val KEY_NAME = "name"
        private const val KEY_SEX = "sex"
        private const val KEY_DOB = "date_of_birth"
        private const val KEY_WEIGHT = "weight_kg"
        private const val KEY_HEIGHT = "height_cm"
        private const val KEY_MAX_HR = "max_hr"
        private const val KEY_RESTING_HR = "resting_hr"
        private const val KEY_ENTRY = "entry_profile"
        private const val KEY_RIDING_NOW = "entry_riding_now"
        private const val KEY_HOURS = "entry_weekly_hours"
        private const val KEY_TIME_OFF = "entry_time_off"
        private const val KEY_INTERVALS = "entry_intervals_before"
        private const val KEY_ENTRY_DONE = "entry_questions_done"
    }
}
