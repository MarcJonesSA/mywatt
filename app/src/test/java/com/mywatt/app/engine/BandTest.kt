package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class BandTest {

    @Test
    fun `bands against a 250 W target`() {
        assertEquals(Band.GREEN, Band.of(250, 250))
        assertEquals(Band.GREEN, Band.of(300, 250))
        assertEquals(Band.GREEN, Band.of(238, 250)) // 95.2%
        assertEquals(Band.YELLOW, Band.of(237, 250)) // 94.8%
        assertEquals(Band.YELLOW, Band.of(225, 250)) // 90%
        assertEquals(Band.ORANGE, Band.of(224, 250))
        assertEquals(Band.ORANGE, Band.of(200, 250)) // 80%
        assertEquals(Band.RED, Band.of(199, 250))
        assertEquals(Band.RED, Band.of(0, 250))
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
        assertEquals("Well under", Band.RED.label)
    }
}
