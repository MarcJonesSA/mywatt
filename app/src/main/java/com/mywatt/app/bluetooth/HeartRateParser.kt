package com.mywatt.app.bluetooth

import kotlin.math.roundToInt

/** Decoded contents of a Heart Rate Measurement notification (characteristic 0x2A37). */
data class HeartRateMeasurement(
    val bpm: Int,
    /** True when the strap supports contact detection and reports the strap is off the skin. */
    val contactLost: Boolean,
    /** RR intervals in milliseconds, in the order sent. Empty when the packet carries none. */
    val rrIntervalsMs: List<Int>,
)

/**
 * Parser for the Bluetooth SIG Heart Rate Service (HRS), which is what a Garmin strap
 * broadcasts its pulse over.
 *
 * Heart Rate Measurement layout:
 *
 *     byte 0        flags
 *       bit 0       1 = heart rate is uint16, 0 = uint8
 *       bits 1-2    sensor contact status: 0b00/0b01 not supported,
 *                   0b10 supported and contact NOT detected, 0b11 supported and detected.
 *                   In bit terms: bit 2 = feature supported, bit 1 = contact detected,
 *                   so "contact lost" is bit 2 set with bit 1 clear (flags & 0x06 == 0x04).
 *       bit 3       energy expended present (uint16 kJ, skipped)
 *       bit 4       RR intervals present (uint16 each, 1/1024 s, zero or more, to end)
 *     byte 1..      heart rate, then energy expended, then RR intervals
 */
object HeartRateParser {

    /** Heart Rate Service. */
    const val HEART_RATE_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"

    /** Heart Rate Measurement characteristic (notify). */
    const val HEART_RATE_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"

    private const val FLAG_HR_UINT16 = 1 shl 0
    private const val FLAG_CONTACT_DETECTED = 1 shl 1
    private const val FLAG_CONTACT_SUPPORTED = 1 shl 2
    private const val FLAG_ENERGY_EXPENDED = 1 shl 3
    private const val FLAG_RR_INTERVALS = 1 shl 4

    /** Returns null if the packet is too short to hold the flags and a heart rate. */
    fun parse(packet: ByteArray): HeartRateMeasurement? {
        if (packet.isEmpty()) return null
        val flags = packet[0].toInt() and 0xFF
        var offset = 1

        val bpm: Int
        if (flags and FLAG_HR_UINT16 != 0) {
            if (packet.size < offset + 2) return null
            bpm = uint16(packet, offset)
            offset += 2
        } else {
            if (packet.size < offset + 1) return null
            bpm = packet[offset].toInt() and 0xFF
            offset += 1
        }

        val contactLost = flags and FLAG_CONTACT_SUPPORTED != 0 &&
            flags and FLAG_CONTACT_DETECTED == 0

        if (flags and FLAG_ENERGY_EXPENDED != 0) offset += 2

        val rr = ArrayList<Int>()
        if (flags and FLAG_RR_INTERVALS != 0) {
            while (offset + 2 <= packet.size) {
                rr += (uint16(packet, offset) * 1000.0 / 1024.0).roundToInt()
                offset += 2
            }
        }

        return HeartRateMeasurement(bpm, contactLost, rr)
    }

    private fun uint16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
}
