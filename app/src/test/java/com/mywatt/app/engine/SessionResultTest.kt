package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionResultTest {

    private fun rep(
        number: Int,
        average: Int,
        recoverySeconds: Int? = 60,
        timedOut: Boolean = false,
        target: Int = 250,
    ) = RepResult(
        repNumber = number,
        targetWatts = target,
        seconds = 60,
        averageWatts = average,
        gear = 12,
        preHr = 120,
        peakHr = 180,
        gateHr = 150,
        hrAt60 = 150,
        hrr60 = 30,
        minuteDrops = listOf(30),
        recoverySeconds = recoverySeconds,
        gateTimedOut = timedOut,
    )

    @Test
    fun `every rep on target`() {
        val reps = listOf(rep(1, 252, 60), rep(2, 249, 65), rep(3, 240, 72))
        assertEquals("All 3 reps on target · Recovery 60 / 65 / 72 s", SessionResult.verdict(reps, completed = true))
    }

    @Test
    fun `some reps missed and a gate timed out`() {
        val reps = listOf(rep(1, 252, 60), rep(2, 230, 65), rep(3, 210, 240, timedOut = true))
        assertEquals(
            "1 of 3 reps on target · Recovery 60 / 65 / 240 s · Gate timed out on rep 3",
            SessionResult.verdict(reps, completed = true),
        )
    }

    @Test
    fun `ended early`() {
        val reps = listOf(rep(1, 252, 60), rep(2, 249, null))
        assertEquals("All 2 reps on target · Recovery 60 s · Ended early", SessionResult.verdict(reps, completed = false))
    }

    @Test
    fun `no reps`() {
        assertEquals("No reps", SessionResult.verdict(emptyList(), completed = true))
        assertEquals("Ended before the first rep", SessionResult.verdict(emptyList(), completed = false))
    }

    @Test
    fun `built from the runner state`() {
        val runner = SessionRunner(Session.threeByOne())
        val result = SessionResult.of(runner.state.value, completed = false)
        assertEquals("Three by One", result.sessionName)
        assertTrue(result.reps.isEmpty())
        assertFalse(result.completed)
        assertEquals("Ended before the first rep", result.verdict)
    }
}
