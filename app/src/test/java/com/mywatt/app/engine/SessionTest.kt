package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionTest {

    @Test
    fun `Three by One is warm-up, three gated reps, cool-down`() {
        val session = Session.threeByOne()

        assertEquals("Three by One", session.name)
        assertEquals(3, session.repCount)
        assertEquals(
            listOf(
                WarmUp(300),
                Rep(60, 250), Recovery(),
                Rep(60, 250), Recovery(),
                Rep(60, 250), Recovery(),
                CoolDown(180),
            ),
            session.blocks,
        )
    }

    @Test
    fun `Three by One takes a target`() {
        val session = Session.threeByOne(targetWatts = 310)
        assertEquals(listOf(310, 310, 310), session.blocks.filterIsInstance<Rep>().map { it.targetWatts })
    }

    @Test
    fun `blocks are tagged for the ride log`() {
        val session = Session.threeByOne()
        assertEquals(
            listOf("warmup", "rep1", "rec1", "rep2", "rec2", "rep3", "rec3", "cooldown"),
            session.blocks.indices.map { session.blockTag(it) },
        )
    }

    @Test
    fun `the default gate waits 60 s for half the rise, times out at 240 s, falls back at 120 s`() {
        val gate = HrGate()
        assertEquals(0.5, gate.recoveredFraction, 1e-9)
        assertEquals(60, gate.minSeconds)
        assertEquals(240, gate.maxSeconds)
        assertEquals(120, gate.fallbackSeconds)
    }

    @Test
    fun `the gate threshold is peak minus half the rise, rounded down`() {
        val gate = HrGate()
        assertEquals(150, gate.thresholdHr(preHr = 120, peakHr = 180))
        // Rise 45: 180 - 22.5 = 157.5, so HR must reach 157.
        assertEquals(157, gate.thresholdHr(preHr = 135, peakHr = 180))
        // No rise at all: the gate is the peak itself.
        assertEquals(160, gate.thresholdHr(preHr = 160, peakHr = 160))
        // A peak below the pre-rep reading cannot happen in practice; treat it as no rise.
        assertEquals(150, gate.thresholdHr(preHr = 170, peakHr = 150))
        assertNull(gate.thresholdHr(preHr = null, peakHr = 180))
        assertNull(gate.thresholdHr(preHr = 120, peakHr = null))
    }

    @Test
    fun `blocks carry a label for the status line`() {
        assertEquals("Warm-up", WarmUp(1).label)
        assertEquals("Rep", Rep(1, 1).label)
        assertEquals("Recovery", Recovery().label)
        assertEquals("Cool-down", CoolDown(1).label)
    }

    @Test
    fun `invalid sessions and gates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Session("empty", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { Session("x", listOf(WarmUp(0))) }
        assertThrows(IllegalArgumentException::class.java) { Session("x", listOf(Rep(60, 0))) }
        assertThrows(IllegalArgumentException::class.java) { Session("x", listOf(Rep(0, 200))) }
        assertThrows(IllegalArgumentException::class.java) { Session("x", listOf(CoolDown(-1))) }
        assertThrows(IllegalArgumentException::class.java) { HrGate(maxSeconds = 59) }
        assertThrows(IllegalArgumentException::class.java) { HrGate(RecoverySetting.Peak, maxSeconds = 29) }
        assertThrows(IllegalArgumentException::class.java) { HrGate(fallbackSeconds = 0) }
    }
}
