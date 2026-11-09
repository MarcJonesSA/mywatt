package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class BandTest {

    @Test
    fun `bands within 2, 3 and 4 percent of a 250 W target`() {
        assertEquals(Band.GREEN, Band.of(250, 250))
        assertEquals(Band.GREEN, Band.of(255, 250)) // +2.0%
        assertEquals(Band.GREEN, Band.of(245, 250)) // -2.0%
        assertEquals(Band.YELLOW, Band.of(256, 250)) // +2.4%
        assertEquals(Band.YELLOW, Band.of(243, 250)) // -2.8%
        assertEquals(Band.ORANGE, Band.of(242, 250)) // -3.2%
        assertEquals(Band.ORANGE, Band.of(260, 250)) // +4.0%
        assertEquals(Band.ORANGE, Band.of(240, 250)) // -4.0%
        assertEquals(Band.RED, Band.of(261, 250))
        assertEquals(Band.RED, Band.of(239, 250))
        assertEquals(Band.RED, Band.of(0, 250))
    }

    @Test
    fun `over target counts the same as under`() {
        assertEquals(Band.of(190, 200), Band.of(210, 200))
        assertEquals(Band.RED, Band.of(210, 200))
        assertEquals(Band.GREEN, Band.of(204, 200))
        assertEquals(Band.GREEN, Band.of(196, 200))
    }

    @Test
    fun `a target of zero is red rather than a division by zero`() {
        assertEquals(Band.RED, Band.of(200, 0))
    }

    @Test
    fun `tags and labels`() {
        assertEquals("green", Band.GREEN.tag)
        assertEquals("red", Band.RED.tag)
        assertEquals("On target", Band.GREEN.label)
        assertEquals("Well off", Band.RED.label)
    }
}
