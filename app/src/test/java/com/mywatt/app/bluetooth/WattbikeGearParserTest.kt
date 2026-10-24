package com.mywatt.app.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WattbikeGearParserTest {

    /** A 20-byte gear notification as the Atom sends it. */
    private fun gearPacket(counter: Int, gear: Int, marker1: Int = 0x03, marker2: Int = 0xB6) =
        ByteArray(20).also {
            it[0] = counter.toByte()
            it[1] = marker1.toByte()
            it[2] = marker2.toByte()
            it[3] = gear.toByte()
        }

    @Test
    fun `decodes the gears seen on the bike`() {
        assertEquals(7, WattbikeGearParser.parseGear(gearPacket(counter = 0x12, gear = 7)))
        assertEquals(21, WattbikeGearParser.parseGear(gearPacket(counter = 0x5A, gear = 21)))
        assertEquals(1, WattbikeGearParser.parseGear(gearPacket(counter = 0xFF, gear = 1)))
    }

    @Test
    fun `ignores packets without the gear markers`() {
        assertNull(WattbikeGearParser.parseGear(gearPacket(0, 7, marker1 = 0x04)))
        assertNull(WattbikeGearParser.parseGear(gearPacket(0, 7, marker2 = 0xB7)))
    }

    @Test
    fun `rejects gears outside 1 to 22`() {
        assertNull(WattbikeGearParser.parseGear(gearPacket(0, 0)))
        assertNull(WattbikeGearParser.parseGear(gearPacket(0, 23)))
        assertNull(WattbikeGearParser.parseGear(gearPacket(0, 0xB6)))
    }

    @Test
    fun `rejects packets too short to hold a gear`() {
        assertNull(WattbikeGearParser.parseGear(byteArrayOf(0x01, 0x03, 0xB6.toByte())))
        assertNull(WattbikeGearParser.parseGear(ByteArray(0)))
    }
}
