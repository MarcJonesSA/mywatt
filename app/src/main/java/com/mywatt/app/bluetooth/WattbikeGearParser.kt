package com.mywatt.app.bluetooth

/**
 * Decodes the gear number from the Wattbike Atom's proprietary service.
 *
 * Found with the GATT discovery log: the Atom does not fill the FTMS resistance-level
 * field, but notifies its gear on a custom characteristic. Each notification is 20 bytes:
 *
 *     byte 0   message counter
 *     byte 1   0x03
 *     byte 2   0xB6
 *     byte 3   gear, 1..22, linear and in the same direction as the Wattbike Hub
 *     byte 4+  zero
 *
 * The bike sends nothing on connect and the first notification arrives about 2 s after
 * pedalling starts; reading the characteristic returns a stale value, so it is not read.
 * The Atom resets to gear 7 at the start of every session; see [GearState].
 *
 * TODO: b4cc1225-bc02-4cae-adb9-1217ad2860d1 (read|write) on the same service is the likely
 * gear-command characteristic. Nothing writes to it yet.
 */
object WattbikeGearParser {

    /** Wattbike custom service. */
    const val WATTBIKE_SERVICE_UUID = "b4cc1223-bc02-4cae-adb9-1217ad2860d1"

    /** Gear characteristic (read | notify). */
    const val GEAR_UUID = "b4cc1224-bc02-4cae-adb9-1217ad2860d1"

    /** Probable gear-command characteristic (read | write). Not used. */
    const val GEAR_COMMAND_UUID = "b4cc1225-bc02-4cae-adb9-1217ad2860d1"

    const val MIN_GEAR = 1
    const val MAX_GEAR = 22

    private const val MARKER_1 = 0x03
    private const val MARKER_2 = 0xB6

    /**
     * Returns the gear carried by [payload], or null if the packet is not a gear message
     * or the gear is out of range.
     */
    fun parseGear(payload: ByteArray): Int? {
        if (payload.size < 4) return null
        if (payload[1].toInt() and 0xFF != MARKER_1) return null
        if (payload[2].toInt() and 0xFF != MARKER_2) return null
        val gear = payload[3].toInt() and 0xFF
        return gear.takeIf { it in MIN_GEAR..MAX_GEAR }
    }
}
