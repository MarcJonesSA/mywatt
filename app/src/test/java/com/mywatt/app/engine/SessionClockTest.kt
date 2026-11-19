package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session runs on the clock, whatever the bike, strap or gear are (or are not) sending. */
class SessionClockTest {

    @Test
    fun `a session completes with null inputs throughout`() {
        val controller = SessionController(Library.byId("three-by-one")!!.build(200))
        val seen = ArrayList<SessionState>()
        var guard = 0
        while (controller.result.value == null) {
            controller.tick(null, null, null, null, null)
            seen += controller.state.value
            check(++guard < 5_000)
        }
        val result = controller.result.value!!
        assertTrue(result.completed)
        // Warm-up 300 s, 3 x (60 s rep + 120 s fallback recovery), cool-down 180 s, then the
        // finishing second.
        assertEquals(300 + 3 * (60 + 120) + 180 + 1, seen.size)
        // The warm-up counted down on its own: 5:00, 4:59, ...
        assertEquals(listOf(300, 299, 298), seen.take(3).map { it.secondsLeft })
        assertEquals(List(3) { null }, result.reps.map { it.averageWatts })
        assertEquals(List(3) { null }, result.reps.map { it.band })
        assertEquals(listOf(120, 120, 120), result.reps.map { it.recoverySeconds })
        assertEquals(
            "0 of 3 reps on target · 3 without power data · Recovery 120 / 120 / 120 s",
            result.verdict,
        )
    }

    @Test
    fun `every library session runs to the end with no data at all`() {
        Library.sessions.forEach { lib ->
            val controller = SessionController(lib.build(200))
            var guard = 0
            while (controller.result.value == null) {
                controller.tick(null, null, null, null, null)
                check(++guard < 10_000) { "${lib.id} stalled without data" }
            }
            assertTrue(lib.id, controller.result.value!!.completed)
        }
    }

    @Test
    fun `a dropout mid-rep does not stop the clock and is left out of the score`() {
        val runner = SessionRunner(Session("Drop", listOf(Rep(20, 200), CoolDown(5))))
        val left = ArrayList<Int?>()
        for (s in 0 until 20) {
            // Seconds 5..13 (9 s) have no power, HR or gear at all.
            val dropped = s in 5..13
            runner.onSecond(s.toLong(), if (dropped) null else 200, if (dropped) null else 150, if (dropped) null else 12)
            left += runner.state.value.secondsLeft
            if (dropped) {
                assertNull(runner.state.value.band)
                assertEquals(200, runner.state.value.liveRepAverage)
            }
        }
        assertEquals((20 downTo 1).toList(), left)
        runner.onSecond(20, 100, 120, 7)
        assertTrue(runner.state.value.block is CoolDown)
        assertEquals(200, runner.state.value.reps.single().averageWatts)
    }
}
