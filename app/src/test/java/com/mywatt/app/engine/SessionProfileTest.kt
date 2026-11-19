package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProfileTest {

    private val session = Session(
        "Shape",
        listOf(WarmUp(300, 100), Rep(60, 300), Recovery(), Rep(60, 300), Easy(90, 110), CoolDown(180, 90)),
        ftpWatts = 200,
    )

    @Test
    fun `one bar per block, width is time, height is percent of FTP`() {
        val bars = SessionProfile.bars(session)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), bars.map { it.blockIndex })
        assertEquals(listOf(300, 60, 120, 60, 90, 180), bars.map { it.seconds })
        assertEquals(listOf(0, 300, 360, 480, 540, 630), bars.map { it.startSeconds })
        assertEquals(150.0, bars[1].startPercent, 1e-9)
        assertEquals(50.0, bars[0].startPercent, 1e-9)
        assertEquals(810, SessionProfile.totalSeconds(bars))
    }

    @Test
    fun `gated recoveries are hatched, low, and drawn at a nominal 2 min`() {
        val gate = SessionProfile.bars(session)[2]
        assertTrue(gate.hatched)
        assertEquals(SessionProfile.GATED_NOMINAL_SECONDS, gate.seconds)
        assertEquals(SessionProfile.GATED_PERCENT, gate.startPercent, 1e-9)
        assertFalse(gate.effort)
        assertFalse(SessionProfile.bars(session)[4].hatched)
    }

    @Test
    fun `the marker moves through a block and holds at the end of a gated one`() {
        val bars = SessionProfile.bars(session)
        assertEquals(330, SessionProfile.positionSeconds(bars, 1, 30))
        // 5 min into a recovery drawn 2 min wide: held at its right edge.
        assertEquals(480, SessionProfile.positionSeconds(bars, 2, 300))
        // Finished: the end.
        assertEquals(810, SessionProfile.positionSeconds(bars, 6, 0))
    }

    @Test
    fun `a ramp is drawn as rising steps and every library session has a profile`() {
        val ramp = Library.sessions.map { it.build(200) }.first { it.ftpTest == FtpTest.RAMP }
        val steps = SessionProfile.bars(ramp).filter { it.effort }
        assertTrue(steps.size > 3)
        assertTrue(steps.zipWithNext().all { (a, b) -> b.startPercent > a.startPercent })
        Library.sessions.forEach { lib ->
            val bars = SessionProfile.bars(lib.build(200))
            assertEquals(lib.id, lib.build(200).blocks.indices.toSet(), bars.map { it.blockIndex }.toSet())
        }
    }
}
