package com.mywatt.app.engine

/**
 * How close a wattage is to a rep's target, for the background tint during a rep and the
 * band column of the results and the ride log. Boundaries are fractions of target, so a
 * 250 W rep is green from 238 W up, yellow from 225 W, orange from 200 W and red below.
 */
enum class Band(val lowerFraction: Double, val label: String) {
    GREEN(0.95, "On target"),
    YELLOW(0.90, "Close"),
    ORANGE(0.80, "Under"),
    RED(0.0, "Well under");

    /** Lower-case tag for the CSV. */
    val tag: String get() = name.lowercase()

    companion object {
        /** The band for [watts] against [targetWatts]. A non-positive target is treated as red. */
        fun of(watts: Int, targetWatts: Int): Band {
            if (targetWatts <= 0) return RED
            val fraction = watts.toDouble() / targetWatts
            return entries.firstOrNull { fraction >= it.lowerFraction } ?: RED
        }
    }
}
