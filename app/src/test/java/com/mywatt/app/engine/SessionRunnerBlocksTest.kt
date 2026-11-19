package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The block types the library added: ramps, sprints, fixed recoveries, sets, back-to-back reps. */
class SessionRunnerBlocksTest {

    private class Ride(session: Session) {
        val runner = SessionRunner(session)
        var second = 0L
        fun tick(watts: Int?, hr: Int? = 120, cadence: Int? = 90, raw: Int? = watts, gear: Int? = 12): SessionState {
            runner.onSecond(second++, watts, hr, gear, raw, cadence)
            return runner.state.value
        }
        fun ticks(n: Int, watts: Int?, hr: Int? = 120, cadence: Int? = 90) = repeat(n) { tick(watts, hr, cadence) }
        val state get() = runner.state.value
    }

    // ---- Ramp -----------------------------------------------------------------------------

    private fun ramp() = Session("Ramp", listOf(Ramp(100, 20), CoolDown(60)), ftpTest = FtpTest.RAMP)

    /** Rides steps 100, 120, 140 W in full, 10 W over each. */
    private fun Ride.threeFullSteps() {
        repeat(3) { step -> ticks(60, 110 + 20 * step) }
    }

    @Test
    fun `the ramp steps up every minute and scores each full step`() {
        val ride = Ride(ramp())
        val first = ride.tick(110)
        assertEquals(100, first.targetWatts)
        assertEquals(1, first.rampStep)
        assertEquals(60, first.secondsLeft)
        assertEquals("ramp", first.blockTag)
        assertEquals("Ramp · step 1 · 100 W · avg 110 W · 1:00 left", first.statusLine)
        ride.ticks(59, 110)
        val second = ride.tick(130)
        assertEquals(120, second.targetWatts)
        assertEquals(2, second.rampStep)
        assertEquals(listOf(100 to 110), second.reps.map { it.targetWatts to it.averageWatts })
        assertEquals(2, second.repNumber)
    }

    @Test
    fun `the ramp ends when cadence stays under 60 for 5 s`() {
        val ride = Ride(ramp())
        ride.threeFullSteps()
        ride.ticks(20, 170) // into step 4 (160 W)
        // Four seconds of low cadence, then it recovers: the ramp carries on.
        ride.ticks(4, 170, cadence = 55)
        ride.tick(170, cadence = 70)
        assertTrue(ride.state.block is Ramp)
        // Five in a row, the last a missing reading: the ramp ends on the fifth.
        ride.ticks(4, 170, cadence = 50)
        assertTrue(ride.state.block is Ramp)
        val ended = ride.tick(170, cadence = null)
        assertEquals(CoolDown(60), ended.block)
        assertEquals(0, ended.secondsInBlock)
        // The step under way (160 W) is dropped; the three full steps stand.
        assertEquals(listOf(100, 120, 140), ended.reps.map { it.targetWatts })
    }

    @Test
    fun `the ramp ends when power stays under the step for 10 s`() {
        val ride = Ride(ramp())
        ride.threeFullSteps()
        // Step 4 is 160 W. Nine seconds under, one at target: not over.
        ride.ticks(9, 150)
        ride.tick(160)
        assertTrue(ride.state.block is Ramp)
        ride.ticks(9, 159)
        assertTrue(ride.state.block is Ramp)
        val ended = ride.tick(null)
        assertEquals(CoolDown(60), ended.block)
        assertEquals(listOf(100, 120, 140), ended.reps.map { it.targetWatts })
    }

    @Test
    fun `the ramp result is 75 percent of the last full step`() {
        val controller = SessionController(ramp())
        // Ride 100..260 W in full (9 steps), then fade in the 280 W step.
        for (step in 0 until 9) repeat(60) { controller.tick(110 + 20 * step, 150, 12, null, 90) }
        repeat(10) { controller.tick(200, 170, 12, null, 90) }
        assertTrue(controller.state.value.block is CoolDown)
        repeat(60) { controller.tick(80, 140, 7, null, 70) }
        val result = controller.result.value!!
        assertTrue(result.completed)
        assertEquals(9, result.reps.size)
        assertEquals(260, result.reps.last().targetWatts)
        assertEquals(195, result.ftpEstimate)
        assertEquals("FTP estimate 195 W · Last full step 260 W", result.verdict)
    }

    @Test
    fun `a ramp that fails in its first step gives no estimate`() {
        val controller = SessionController(ramp())
        repeat(10) { controller.tick(50, 120, 7, null, 90) }
        assertTrue(controller.state.value.block is CoolDown)
        controller.end()
        assertNull(controller.result.value!!.ftpEstimate)
        assertTrue(controller.result.value!!.reps.isEmpty())
    }

    // ---- 20-minute test ------------------------------------------------------------------

    @Test
    fun `the 20-minute result is 95 percent of the average`() {
        val lib = Library.byId("test-20")!!
        val controller = SessionController(lib.build(250))
        repeat(600 + 300) { controller.tick(150, 130, 10) }
        assertTrue(controller.state.value.block is Easy)
        controller.tick(260, 170, 14)
        val effort = controller.state.value
        assertEquals(Rep(1200, 250, kind = RepKind.SELF_PACED), effort.block)
        // The target shown during the effort is the current FTP.
        assertEquals(250, effort.targetWatts)
        assertEquals("Test effort", effort.block!!.label)
        // 10 min at 260 W and 10 min at 241 W: average 250.5, rounded to 251 W.
        repeat(599) { controller.tick(260, 170, 14) }
        repeat(600) { controller.tick(241, 175, 14) }
        repeat(300) { controller.tick(120, 140, 8) }
        assertFalse(controller.state.value.finished)
        controller.tick(120, 140, 8)
        assertTrue(controller.state.value.finished)
        val result = controller.result.value!!
        assertEquals(listOf(251), result.reps.map { it.averageWatts })
        // 0.95 * 251 = 238.45.
        assertEquals(238, result.ftpEstimate)
        assertEquals("FTP estimate 238 W · 20 min average 251 W", result.verdict)
        assertEquals(FtpTest.TWENTY_MINUTE.estimate(listOf(rep(average = 230))), 219) // 218.5 rounds up
    }

    private fun rep(average: Int) = RepResult(1, 250, 1200, average, null, null, null, null, null, null, emptyList(), null, false)

    // ---- Sprints -------------------------------------------------------------------------

    @Test
    fun `a sprint is scored on its best 10 s of raw watts`() {
        val session = Session(
            "Sprint",
            listOf(Rep(14, 500, bestWindowSeconds = 10, kind = RepKind.SPRINT), Easy(5, 100)),
        )
        val ride = Ride(session)
        // Raw: slow start, 4 s at 400, 10 s at 700 then... smoothed is irrelevant to the score.
        val raw = listOf(300, 400, 400, 400, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700)
        raw.forEach { ride.tick(watts = 450, raw = it) }
        val after = ride.tick(100)
        assertEquals(listOf(700), after.reps.map { it.averageWatts })
        assertEquals("Sprint", session.blocks[0].label)

        assertNull(SessionRunner.bestAverage(emptyList(), 10))
        assertNull(SessionRunner.bestAverage(listOf(null, null), 10))
        // A missing second is left out of its window rather than counted as 0 W.
        assertEquals(600, SessionRunner.bestAverage(listOf(100, 600, null, 600), 2))
        assertEquals(200, SessionRunner.bestAverage(listOf(100, 300), 10))
        assertEquals(550, SessionRunner.bestAverage(listOf(100, 500, 600, 200), 2))
    }

    // ---- Fixed recoveries, sets, cadence ---------------------------------------------------

    @Test
    fun `fixed recoveries run on the clock with their own target`() {
        val session = Session("Fixed", listOf(WarmUp(3, 100), Rep(5, 200), Easy(4, 110), Rep(5, 200), CoolDown(3, 90)))
        val ride = Ride(session)
        assertEquals("Warm-up · 100 W · 0:03 left", ride.tick(100).statusLine)
        assertEquals(100, ride.state.easyTargetWatts)
        assertNull(ride.state.targetWatts)
        ride.ticks(2, 100)
        ride.ticks(5, 200)
        val easy = ride.tick(110, hr = null)
        assertEquals(Easy(4, 110), easy.block)
        assertEquals("rec1", easy.blockTag)
        assertEquals(110, easy.easyTargetWatts)
        assertNull(easy.band)
        assertEquals(4, easy.secondsLeft)
        assertEquals("Easy · 110 W · 0:04 left", easy.statusLine)
        ride.ticks(3, 110)
        assertEquals(Rep(5, 200), ride.tick(200).block)
        // No gate: the rep's recovery time stays empty.
        assertNull(ride.state.reps.single().recoverySeconds)
    }

    @Test
    fun `reps report their set and cadence target`() {
        val s = Library.byId("ronnestad-30-15")!!.build(200)
        val ride = Ride(s)
        ride.ticks(300, 110)
        val first = ride.tick(240)
        assertEquals(1, first.setNumber)
        assertEquals(1, first.repNumber)
        assertEquals("Rep 1/39 · 240 W · avg 240 W · 0:30 left", first.statusLine)

        val force = Ride(Library.byId("low-cadence-force")!!.build(200))
        force.ticks(300, 110)
        assertEquals(60, force.tick(170, cadence = 60).cadenceTarget)
    }

    @Test
    fun `the gear nag is off for a steady block`() {
        val ride = Ride(Session("Z2", listOf(Rep(60, 130, kind = RepKind.STEADY))))
        assertFalse(ride.tick(130, hr = 120, cadence = 90, raw = 130, gear = 3).gearNag)
        assertEquals("Steady 1/1 · 130 W · avg 130 W · 1:00 left", ride.state.statusLine)
    }

    // ---- Back-to-back reps ---------------------------------------------------------------

    @Test
    fun `reps ridden back to back are one effort for the gate`() {
        // Stepdown shape: three 20 s reps, then a gated recovery.
        val session = Session("Step", listOf(Rep(20, 260), Rep(20, 234), Rep(20, 210), Recovery(), CoolDown(5)))
        val ride = Ride(session)
        // HR climbs from 100 over the whole minute: 100..159.
        for (i in 0 until 60) ride.tick(240, hr = 100 + i)
        val recovering = ride.tick(100, hr = 160)
        assertTrue(recovering.block is Recovery)
        val last = recovering.reps.last()
        // The third rep alone started at 140, but the gate counts the rise from 100:
        // peak 160, rise 60, half of it recovered at 130.
        assertEquals(140, last.preHr)
        assertEquals(130, last.gateHr)
        assertEquals(130, recovering.gateHr)
        assertEquals(listOf(100, 120, 140), recovering.reps.map { it.preHr })
    }
}
