package com.mywatt.app.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateParserTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `decodes a uint8 heart rate with no RR intervals`() {
        // Flags 0x06: uint8 HR, contact supported and detected.
        val m = HeartRateParser.parse(bytes(0x06, 142))!!

        assertEquals(142, m.bpm)
        assertFalse(m.contactLost)
        assertTrue(m.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `decodes a uint16 heart rate`() {
        // Flags 0x01: uint16 HR. 0x012C = 300 bpm, little-endian.
        val m = HeartRateParser.parse(bytes(0x01, 0x2C, 0x01))!!

        assertEquals(300, m.bpm)
        assertFalse(m.contactLost)
    }

    @Test
    fun `decodes one RR interval`() {
        // Flags 0x10: uint8 HR, RR present. 1024/1024 s = 1000 ms.
        val m = HeartRateParser.parse(bytes(0x10, 60, 0x00, 0x04))!!

        assertEquals(60, m.bpm)
        assertEquals(listOf(1000), m.rrIntervalsMs)
    }

    @Test
    fun `decodes three RR intervals and skips energy expended`() {
        // Flags 0x18: uint8 HR, energy expended present, RR present.
        // Energy 0x1234 is skipped; RRs 512, 768, 1024 ticks = 500, 750, 1000 ms.
        val m = HeartRateParser.parse(
            bytes(0x18, 90, 0x34, 0x12, 0x00, 0x02, 0x00, 0x03, 0x00, 0x04)
        )!!

        assertEquals(90, m.bpm)
        assertEquals(listOf(500, 750, 1000), m.rrIntervalsMs)
    }

    @Test
    fun `reports contact lost when supported but not detected`() {
        // Flags 0x04: contact feature supported (bit 2), contact not detected (bit 1 clear).
        assertTrue(HeartRateParser.parse(bytes(0x04, 0))!!.contactLost)
        // Flags 0x06: supported and detected.
        assertFalse(HeartRateParser.parse(bytes(0x06, 120))!!.contactLost)
        // Flags 0x00 / 0x02: feature not supported, so never "lost".
        assertFalse(HeartRateParser.parse(bytes(0x00, 120))!!.contactLost)
        assertFalse(HeartRateParser.parse(bytes(0x02, 120))!!.contactLost)
    }

    @Test
    fun `returns null for packets too short to hold a heart rate`() {
        assertNull(HeartRateParser.parse(ByteArray(0)))
        assertNull(HeartRateParser.parse(bytes(0x00)))
        assertNull(HeartRateParser.parse(bytes(0x01, 0x50)))
    }
}
