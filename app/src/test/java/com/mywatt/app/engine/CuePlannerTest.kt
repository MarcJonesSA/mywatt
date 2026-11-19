package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuePlannerTest {

    /** Rides [session] on target, returning (block index, seconds in block, cue text) for every cue. */
    private fun ride(
        session: Session,
        settings: CueSettings = CueSettings(),
        hr: (SessionState) -> Int? = { 120 },
    ): List<Triple<Int, Int, String>> {
        val runner = SessionRunner(session)
        val planner = CuePlanner(settings)
        val heard = ArrayList<Triple<Int, Int, String>>()
        var t = 0L
        var state = runner.state.value
        while (!state.finished) {
            val watts = state.targetWatts ?: 100
            runner.onSecond(t++, watts, hr(state), 12, watts, 90)
            state = runner.state.value
            planner.cues(state).forEach { heard += Triple(state.blockIndex, state.secondsInBlock, it.text) }
            check(t < 20_000)
        }
        return heard
    }

    private fun texts(heard: List<Triple<Int, Int, String>>, block: Int) = heard.filter { it.first == block }.map { it.third }

    @Test
    fun `a short rep gets a count-in, go, and a count from 10`() {
        val heard = ride(Session("S", listOf(WarmUp(10), Rep(20, 300), CoolDown(5))))
        assertEquals(listOf("3", "2", "1"), texts(heard, 0))
        assertEquals(listOf("Go") + (10 downTo 1).map { "$it" }, texts(heard, 1))
        // The count-in lands on the last three seconds of the warm-up.
        assertEquals(listOf(7, 8, 9), heard.filter { it.first == 0 }.map { it.second })
    }

    @Test
    fun `repeated 30-15 reps count from 5, the last of a set from 10`() {
        val set = listOf(Rep(30, 300, set = 1), Easy(15, 100), Rep(30, 300, set = 1), Easy(15, 100), Rep(30, 300, set = 1))
        val heard = ride(Session("R", listOf(WarmUp(10)) + set + listOf(CoolDown(5))))
        assertEquals(listOf("Go", "5", "4", "3", "2", "1"), texts(heard, 1))
        assertEquals(listOf("3", "2", "1"), texts(heard, 2))
        assertEquals(listOf("Go") + (10 downTo 1).map { "$it" }, texts(heard, 5))
    }

    @Test
    fun `a mid rep says halfway, 30 to go and counts from 10`() {
        val heard = ride(Session("M", listOf(WarmUp(10), Rep(120, 250), CoolDown(5))))
        assertEquals(listOf("Halfway", "30 to go") + (10 downTo 1).map { "$it" }, texts(heard, 1))
        assertEquals(60, heard.first { it.third == "Halfway" }.second)
        // No count-in or go for a rep over 30 s.
        assertTrue(texts(heard, 0).isEmpty())
    }

    @Test
    fun `a long rep says elapsed every 5 min, 5 to go, 1 to go and push in the last 5 s`() {
        val heard = ride(Session("L", listOf(Rep(1200, 220), CoolDown(5))))
        assertEquals(listOf("5 minutes", "10 minutes", "5 to go", "1 to go", "Push"), texts(heard, 0))
        assertEquals(1195, heard.first { it.third == "Push" }.second)
    }

    @Test
    fun `the gate says coming down, recovered with a 10 s lead-in, and max rest on a timeout`() {
        val gate = HrGate(RecoverySetting.Building, leadInSeconds = 10)
        val session = Session("G", listOf(Rep(60, 250), Recovery(gate), Rep(60, 250), Recovery(gate), Rep(60, 250), CoolDown(5)))
        var recoverySeconds = 0
        val heard = ride(session, hr = { s ->
            when {
                s.block is Rep -> { recoverySeconds = 0; 120 + s.secondsInBlock }
                s.blockIndex == 1 -> 180 - (recoverySeconds++) / 2 // comes down
                else -> 180 // stays up: times out
            }
        })
        val first = texts(heard, 1)
        assertEquals(listOf("HR coming down", "Recovered, next rep in 10"), first)
        assertTrue(texts(heard, 3).isEmpty())
        assertEquals("That's the max rest, go", texts(heard, 4).first())
    }

    @Test
    fun `with no HR the fallback warns 10 s before the next rep`() {
        val session = Session("F", listOf(Rep(60, 250), Recovery(HrGate()), Rep(20, 250), CoolDown(5)))
        val heard = ride(session, hr = { null })
        assertEquals(listOf("Next rep in 10", "3", "2", "1"), texts(heard, 1))
        assertEquals(110, heard.first { it.third == "Next rep in 10" }.second)
    }

    @Test
    fun `settings switch cues off, per tier and altogether`() {
        val session = Session("S", listOf(WarmUp(10), Rep(20, 300), Rep(120, 250), CoolDown(5)))
        assertTrue(ride(session, CueSettings(enabled = false)).isEmpty())
        val noShort = ride(session, CueSettings(shortTier = false))
        assertTrue(texts(noShort, 0).isEmpty() && texts(noShort, 1).isEmpty())
        assertTrue(texts(noShort, 2).contains("Halfway"))
        assertTrue(texts(ride(session, CueSettings(midTier = false)), 2).isEmpty())
    }

    @Test
    fun `a paused second says nothing twice`() {
        val runner = SessionRunner(Session("P", listOf(WarmUp(10), Rep(20, 300))))
        val planner = CuePlanner()
        repeat(8) { runner.onSecond(it.toLong(), 100, 120, 12) }
        assertEquals(listOf("3"), planner.cues(runner.state.value).map { it.text })
        assertTrue(planner.cues(runner.state.value).isEmpty())
    }

    @Test
    fun `push words only in the last 5 s of a rep, across the whole library`() {
        Library.sessions.forEach { lib ->
            val session = lib.build(200)
            val runner = SessionRunner(session)
            val planner = CuePlanner()
            var t = 0L
            while (!runner.state.value.finished && t < 20_000) {
                val s = runner.state.value
                val w = (s.targetWatts ?: 100).let { if (s.block is Ramp && it >= 280) 0 else it }
                runner.onSecond(t++, w, 140, 12, w, 90)
                val now = runner.state.value
                planner.cues(now).filter { it.text in setOf("Push", "Keep going") }.forEach {
                    assertTrue(lib.id, now.block is Rep && (now.secondsLeft ?: 99) <= 5)
                }
            }
        }
    }
}
