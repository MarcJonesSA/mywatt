package com.mywatt.app.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GearStateTest {

    private fun gearPacket(gear: Int) = ByteArray(20).also {
        it[0] = 0x01
        it[1] = 0x03
        it[2] = 0xB6.toByte()
        it[3] = gear.toByte()
    }

    @Test
    fun `starts in gear 7 unconfirmed`() {
        val state = GearState()
        assertEquals(GearReading(7, confirmed = false), state.reading)
    }

    @Test
    fun `confirms on the first valid notification and stays confirmed`() {
        val state = GearState()

        assertTrue(state.onNotification(gearPacket(7)))
        assertEquals(GearReading(7, confirmed = true), state.reading)

        assertTrue(state.onNotification(gearPacket(12)))
        assertEquals(GearReading(12, confirmed = true), state.reading)

        // A later garbage payload changes nothing and does not un-confirm.
        assertFalse(state.onNotification(byteArrayOf(0x01, 0x00, 0x00, 0x05)))
        assertEquals(GearReading(12, confirmed = true), state.reading)
    }

    @Test
    fun `ignores invalid payloads before the first real gear`() {
        val state = GearState()
        assertFalse(state.onNotification(ByteArray(0)))
        assertFalse(state.onNotification(gearPacket(23)))
        assertEquals(GearReading(7, confirmed = false), state.reading)
    }

    @Test
    fun `reset returns to the unconfirmed start gear`() {
        val state = GearState()
        state.onNotification(gearPacket(15))
        state.reset()
        assertEquals(GearReading(7, confirmed = false), state.reading)
    }
}
