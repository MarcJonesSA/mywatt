package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reps of 30 s or less are scored on raw watts after a 2 s ramp-in; longer reps on smoothed. */
class ShortRepScoringTest {

    private fun ride(rep: Rep, smoothed: List<Int?>, raw: List<Int?>): SessionRunner {
        val runner = SessionRunner(Session("S", listOf(rep, CoolDown(5))))
        smoothed.indices.forEach { runner.onSecond(it.toLong(), smoothed[it], 150, 12, raw[it]) }
        runner.onSecond(smoothed.size.toLong(), 100, 140, 7, 100)
        return runner
    }

    @Test
    fun `a 20 s rep with a 2 s ramp-in scores green`() {
        // Raw: 2 s getting up to speed, then on target. Smoothed lags well behind.
        val raw = listOf(80, 200) + List(18) { 300 }
        val smoothed = listOf(40, 140, 200, 240, 270) + List(15) { 290 }
        val rep = ride(Rep(20, 300), smoothed, raw).state.value.reps.single()
        assertEquals(300, rep.averageWatts)
        assertEquals(Band.GREEN, rep.band)
    }

    @Test
    fun `no band during the ramp-in, then the raw watts are judged live`() {
        val runner = SessionRunner(Session("S", listOf(Rep(20, 300), CoolDown(5))))
        runner.onSecond(0, 50, 150, 12, 80)
        assertNull(runner.state.value.band)
        runner.onSecond(1, 120, 150, 12, 200)
        assertNull(runner.state.value.band)
        runner.onSecond(2, 180, 150, 12, 300)
        assertEquals(Band.GREEN, runner.state.value.band)
        assertEquals(300, runner.state.value.liveRepAverage)
    }

    @Test
    fun `reps over 30 s still score on smoothed watts`() {
        val raw = List(40) { 400 }
        val smoothed = List(40) { 300 }
        assertEquals(300, ride(Rep(40, 300), smoothed, raw).state.value.reps.single().averageWatts)
        assertFalse(Rep(31, 300).scoresOnRaw)
        assertTrue(Rep(30, 300).scoresOnRaw)
    }

    @Test
    fun `sprints keep their best-window rule`() {
        val sprint = Rep(14, 500, bestWindowSeconds = 10, kind = RepKind.SPRINT)
        assertFalse(sprint.scoresOnRaw)
        val raw = listOf(300, 400, 400, 400) + List(10) { 700 }
        assertEquals(700, ride(sprint, List(14) { 450 }, raw).state.value.reps.single().averageWatts)
    }

    @Test
    fun `a short rep with no raw power after the ramp-in is missing`() {
        val raw = listOf(200, 200) + List(18) { null }
        assertNull(ride(Rep(20, 300), List(20) { 300 }, raw).state.value.reps.single().averageWatts)
    }
}
