package com.mywatt.app.bluetooth

/** The gear to show and log, and whether the bike itself has reported it this connection. */
data class GearReading(val gear: Int, val confirmed: Boolean)

/**
 * Tracks the Atom's gear across one connection.
 *
 * The bike resets to gear 7 at the start of every session and sends nothing on connect; its
 * first b4cc1224 notification arrives a couple of seconds after pedalling starts. So the
 * state begins as gear 7 *unconfirmed* (drawn greyed out, logged with gear_confirmed=0) and
 * flips to confirmed on the first valid notification, after which it simply follows the
 * bike. Reading the characteristic on connect is not done: on the bike it returned a stale
 * value that was then wrongly treated as confirmed.
 */
class GearState {

    var reading: GearReading = INITIAL
        private set

    /**
     * Feeds a notification from the gear characteristic. Returns true if it carried a valid
     * gear and the reading changed or became confirmed; invalid payloads are ignored.
     */
    fun onNotification(payload: ByteArray): Boolean {
        val gear = WattbikeGearParser.parseGear(payload) ?: return false
        val next = GearReading(gear, confirmed = true)
        if (next == reading) return false
        reading = next
        return true
    }

    /** Back to the unconfirmed start-of-session gear, for a new connection. */
    fun reset() {
        reading = INITIAL
    }

    companion object {
        /** The gear the Atom is in at the start of every session. */
        const val START_GEAR = 7

        val INITIAL = GearReading(START_GEAR, confirmed = false)
    }
}
