package com.mywatt.app.bluetooth

/**
 * Decoded contents of an FTMS Indoor Bike Data notification (characteristic 0x2AD2).
 *
 * Every field is optional: the trainer advertises in the packet's flags which fields
 * it actually sends, and that set can change from one notification to the next.
 */
data class IndoorBikeData(
    val instantaneousSpeedKph: Double? = null,
    val averageSpeedKph: Double? = null,
    val instantaneousCadenceRpm: Double? = null,
    val averageCadenceRpm: Double? = null,
    val totalDistanceMetres: Int? = null,
    val resistanceLevel: Int? = null,
    val instantaneousPowerWatts: Int? = null,
    val averagePowerWatts: Int? = null,
    val totalEnergyKcal: Int? = null,
    val energyPerHourKcal: Int? = null,
    val energyPerMinuteKcal: Int? = null,
    val heartRateBpm: Int? = null,
    val metabolicEquivalent: Double? = null,
    val elapsedTimeSeconds: Int? = null,
    val remainingTimeSeconds: Int? = null,
)

/**
 * Parser for the Bluetooth SIG Fitness Machine Service (FTMS), which is what the
 * Wattbike Atom broadcasts its live metrics over.
 *
 * The Indoor Bike Data characteristic is a 16-bit little-endian flags field followed by
 * a variable-length run of fields. Each field is present only when its flag bit is set,
 * and the fields always appear in the order defined by the specification, so the packet
 * has to be walked from the front — there are no fixed offsets.
 */
object FTMSParser {

    /** Fitness Machine Service. */
    const val FTMS_SERVICE_UUID = "00001826-0000-1000-8000-00805f9b34fb"

    /** Indoor Bike Data characteristic (notify). */
    const val INDOOR_BIKE_DATA_UUID = "00002ad2-0000-1000-8000-00805f9b34fb"

    /** Fitness Machine Feature characteristic (read). */
    const val FTMS_FEATURE_UUID = "00002acc-0000-1000-8000-00805f9b34fb"

    /** Client Characteristic Configuration descriptor, used to switch notifications on. */
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    // Flag bits of the Indoor Bike Data characteristic.
    // Note that bit 0 is inverted: speed is present when "More Data" is *clear*.
    private const val FLAG_MORE_DATA = 1 shl 0
    private const val FLAG_AVERAGE_SPEED = 1 shl 1
    private const val FLAG_INSTANTANEOUS_CADENCE = 1 shl 2
    private const val FLAG_AVERAGE_CADENCE = 1 shl 3
    private const val FLAG_TOTAL_DISTANCE = 1 shl 4
    private const val FLAG_RESISTANCE_LEVEL = 1 shl 5
    private const val FLAG_INSTANTANEOUS_POWER = 1 shl 6
    private const val FLAG_AVERAGE_POWER = 1 shl 7
    private const val FLAG_EXPENDED_ENERGY = 1 shl 8
    private const val FLAG_HEART_RATE = 1 shl 9
    private const val FLAG_METABOLIC_EQUIVALENT = 1 shl 10
    private const val FLAG_ELAPSED_TIME = 1 shl 11
    private const val FLAG_REMAINING_TIME = 1 shl 12

    /** Sentinel the specification uses for "this value is not available". */
    private const val UINT16_NOT_AVAILABLE = 0xFFFF

    /**
     * Decodes an Indoor Bike Data notification.
     *
     * Returns null if the packet is too short to even contain the flags. If the packet ends
     * early — a trainer setting a flag it then fails to fill in — the fields decoded up to
     * that point are returned rather than throwing, so a single malformed notification cannot
     * take the ride screen down.
     */
    fun parseIndoorBikeData(packet: ByteArray): IndoorBikeData? {
        val reader = PacketReader(packet)
        val flags = reader.uint16() ?: return null

        var data = IndoorBikeData()

        // C1: instantaneous speed, present when the More Data bit is CLEAR. uint16, 0.01 km/h.
        if (flags and FLAG_MORE_DATA == 0) {
            val raw = reader.uint16() ?: return data
            data = data.copy(instantaneousSpeedKph = raw / 100.0)
        }
        // C2: average speed. uint16, 0.01 km/h.
        if (flags and FLAG_AVERAGE_SPEED != 0) {
            val raw = reader.uint16() ?: return data
            data = data.copy(averageSpeedKph = raw / 100.0)
        }
        // C3: instantaneous cadence. uint16, 0.5 rpm.
        if (flags and FLAG_INSTANTANEOUS_CADENCE != 0) {
            val raw = reader.uint16() ?: return data
            data = data.copy(instantaneousCadenceRpm = raw / 2.0)
        }
        // C4: average cadence. uint16, 0.5 rpm.
        if (flags and FLAG_AVERAGE_CADENCE != 0) {
            val raw = reader.uint16() ?: return data
            data = data.copy(averageCadenceRpm = raw / 2.0)
        }
        // C5: total distance. uint24, metres.
        if (flags and FLAG_TOTAL_DISTANCE != 0) {
            val raw = reader.uint24() ?: return data
            data = data.copy(totalDistanceMetres = raw)
        }
        // C6: resistance level. sint16, unitless.
        if (flags and FLAG_RESISTANCE_LEVEL != 0) {
            val raw = reader.sint16() ?: return data
            data = data.copy(resistanceLevel = raw)
        }
        // C7: instantaneous power. sint16, watts.
        if (flags and FLAG_INSTANTANEOUS_POWER != 0) {
            val raw = reader.sint16() ?: return data
            data = data.copy(instantaneousPowerWatts = raw)
        }
        // C8: average power. sint16, watts.
        if (flags and FLAG_AVERAGE_POWER != 0) {
            val raw = reader.sint16() ?: return data
            data = data.copy(averagePowerWatts = raw)
        }
        // C9: expended energy — three fields under a single flag.
        if (flags and FLAG_EXPENDED_ENERGY != 0) {
            val total = reader.uint16() ?: return data
            data = data.copy(totalEnergyKcal = total.takeIf { it != UINT16_NOT_AVAILABLE })
            val perHour = reader.uint16() ?: return data
            data = data.copy(energyPerHourKcal = perHour.takeIf { it != UINT16_NOT_AVAILABLE })
            val perMinute = reader.uint8() ?: return data
            data = data.copy(energyPerMinuteKcal = perMinute.takeIf { it != 0xFF })
        }
        // C10: heart rate. uint8, bpm.
        if (flags and FLAG_HEART_RATE != 0) {
            val raw = reader.uint8() ?: return data
            data = data.copy(heartRateBpm = raw)
        }
        // C11: metabolic equivalent. uint8, 0.1.
        if (flags and FLAG_METABOLIC_EQUIVALENT != 0) {
            val raw = reader.uint8() ?: return data
            data = data.copy(metabolicEquivalent = raw / 10.0)
        }
        // C12: elapsed time. uint16, seconds.
        if (flags and FLAG_ELAPSED_TIME != 0) {
            val raw = reader.uint16() ?: return data
            data = data.copy(elapsedTimeSeconds = raw)
        }
        // C13: remaining time. uint16, seconds.
        if (flags and FLAG_REMAINING_TIME != 0) {
            val raw = reader.uint16() ?: return data
            data = data.copy(remainingTimeSeconds = raw)
        }

        return data
    }

    /** Little-endian cursor over a notification payload. Every read returns null at end of packet. */
    private class PacketReader(private val bytes: ByteArray) {
        private var offset = 0

        private fun byteAt(index: Int): Int = bytes[index].toInt() and 0xFF

        fun uint8(): Int? {
            if (offset + 1 > bytes.size) return null
            return byteAt(offset).also { offset += 1 }
        }

        fun uint16(): Int? {
            if (offset + 2 > bytes.size) return null
            val value = byteAt(offset) or (byteAt(offset + 1) shl 8)
            offset += 2
            return value
        }

        fun sint16(): Int? {
            val value = uint16() ?: return null
            return if (value >= 0x8000) value - 0x10000 else value
        }

        fun uint24(): Int? {
            if (offset + 3 > bytes.size) return null
            val value = byteAt(offset) or (byteAt(offset + 1) shl 8) or (byteAt(offset + 2) shl 16)
            offset += 3
            return value
        }
    }
}
