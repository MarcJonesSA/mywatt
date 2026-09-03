package com.mywatt.app.data

import androidx.annotation.ColorRes
import com.mywatt.app.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Training zones, expressed as a fraction of FTP so that every boundary moves with the
 * rider's threshold rather than being hard-coded in watts.
 *
 * @param lowerFraction inclusive lower bound as a fraction of FTP.
 * @param upperFraction exclusive upper bound, or null for the open-ended top zone.
 */
enum class PowerZone(
    val number: Int,
    val label: String,
    val lowerFraction: Double,
    val upperFraction: Double?,
    @ColorRes val colorRes: Int,
) {
    Z1(1, "Recovery", 0.00, 0.55, R.color.zone_1),
    Z2(2, "Endurance", 0.55, 0.75, R.color.zone_2),
    Z3(3, "Tempo", 0.75, 0.90, R.color.zone_3),
    Z4(4, "Threshold", 0.90, 1.05, R.color.zone_4),
    Z5(5, "VO2 Max", 1.05, 1.20, R.color.zone_5),
    Z6(6, "Anaerobic", 1.20, null, R.color.zone_6);

    /** Lowest wattage that counts as this zone for the given FTP. */
    fun lowerWatts(ftp: Int): Int = (lowerFraction * ftp).roundToInt()

    /** Highest wattage in this zone, or null for the open-ended top zone. */
    fun upperWatts(ftp: Int): Int? = upperFraction?.let { (it * ftp).roundToInt() - 1 }

    /** e.g. "165 - 197 W", or "265+ W" for the top zone. */
    fun rangeLabel(ftp: Int): String {
        val upper = upperWatts(ftp)
        return if (upper == null) "${lowerWatts(ftp)}+ W" else "${lowerWatts(ftp)} - $upper W"
    }
}

object PowerZones {

    /** The zone a wattage falls in for the given FTP. */
    fun zoneFor(watts: Int, ftp: Int): PowerZone {
        if (ftp <= 0) return PowerZone.Z1
        val fraction = watts.toDouble() / ftp
        // Walk down from the top so the open-ended zone catches everything above it.
        return PowerZone.entries.lastOrNull { fraction >= it.lowerFraction } ?: PowerZone.Z1
    }

    /**
     * Full-scale reading of the rev counter: twice FTP, rounded up to a tidy 50 W mark, so
     * threshold sits in the middle of the dial and there is headroom for a sprint.
     */
    fun gaugeMaxWatts(ftp: Int): Int {
        val raw = ftp * 2.0
        val rounded = (ceil(raw / GAUGE_STEP_WATTS) * GAUGE_STEP_WATTS).toInt()
        return rounded.coerceIn(MIN_GAUGE_WATTS, MAX_GAUGE_WATTS)
    }

    /**
     * The zones as contiguous wattage spans covering 0 W to [gaugeMaxWatts], for painting
     * the coloured arc. The top zone is clipped to the end of the dial.
     */
    fun gaugeSpans(ftp: Int): List<ZoneSpan> {
        val max = gaugeMaxWatts(ftp)
        return PowerZone.entries.mapNotNull { zone ->
            val start = zone.lowerWatts(ftp).coerceIn(0, max)
            val end = (zone.upperFraction?.let { (it * ftp).roundToInt() } ?: max).coerceIn(0, max)
            if (end <= start) null else ZoneSpan(zone, start, end)
        }
    }

    /** A zone as it appears on the dial: [startWatts] up to [endWatts]. */
    data class ZoneSpan(val zone: PowerZone, val startWatts: Int, val endWatts: Int)

    private const val GAUGE_STEP_WATTS = 50.0
    private const val MIN_GAUGE_WATTS = 300
    private const val MAX_GAUGE_WATTS = 2000
}
