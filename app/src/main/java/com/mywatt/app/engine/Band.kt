package com.mywatt.app.engine

import kotlin.math.abs

/**
 * How close a wattage is to a rep's target, in either direction, for the background tint
 * during a rep and the band column of the results and the ride log. Boundaries are
 * fractions of target, so for a 250 W rep green is 245..255 W, yellow out to 242.5 and
 * 257.5 W, orange out to 240 and 260 W, and red beyond.
 */
enum class Band(val maxDeviation: Double, val label: String) {
    GREEN(0.02, "On target"),
    YELLOW(0.03, "Close"),
    ORANGE(0.04, "Off target"),
    RED(Double.POSITIVE_INFINITY, "Well off");

    /** Lower-case tag for the CSV. */
    val tag: String get() = name.lowercase()

    companion object {
        /** The band for [watts] against [targetWatts]. A non-positive target is treated as red. */
        fun of(watts: Int, targetWatts: Int): Band {
            if (targetWatts <= 0) return RED
            val deviation = abs(watts - targetWatts).toDouble() / targetWatts
            return entries.first { deviation <= it.maxDeviation }
        }
    }
}
