package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRunnerTest {

    /** One second of synthetic ride data. */
    private data class Tick(val watts: Int?, val hr: Int?, val gear: Int? = 7)

    /** Feeds every tick from second 0 and returns the state after each one, indexed by second. */
    private fun drive(runner: SessionRunner, ride: List<Tick>): List<SessionState> =
        ride.mapIndexed { second, tick ->
            runner.onSecond(second.toLong(), tick.watts, tick.hr, tick.gear)
            runner.state.value
        }

    // ---- The built-in session, ridden end to end -------------------------------------------
    //
    // Timeline: warm-up 0..299; rep 1 300..359; recovery 1 360..419 (gate opens at 420);
    // rep 2 420..479; recovery 2 480..539; rep 3 540..599; recovery 3 600..659;
    // cool-down 660..839; finished on 840.

    private fun easy(seconds: Int) = List(seconds) { Tick(100, 110, 7) }

    /** 240 W for 30 s then 260 W for 30 s (average 250), HR climbing 120..179, gear 12. */
    private fun rep() = List(60) { i -> Tick(if (i < 30) 240 else 260, 120 + i, 12) }

    /**
     * HR keeps climbing to 185 for the first 10 s after the rep, then falls 1 bpm a second.
     * Pre-rep HR was 120, so the rise is 65 and the gate is 152 (185 - 32.5, rounded down),
     * reached at 43 s; the gate still waits for 60 s.
     */
    private fun recovery() = List(60) { s ->
        Tick(90, if (s <= 10) minOf(185, 180 + s) else 185 - (s - 10), 7)
    }

    private fun threeByOneRide() =
        easy(300) + rep() + recovery() + rep() + recovery() + rep() + recovery() + easy(180) + easy(1)

    @Test
    fun `ready state before the first second`() {
        val runner = SessionRunner(Session.threeByOne())
        val state = runner.state.value

        assertEquals(0, state.blockIndex)
        assertEquals(WarmUp(300), state.block)
        assertEquals("warmup", state.blockTag)
        assertEquals(300, state.secondsLeft)
        assertEquals(0, state.repNumber)
        assertNull(state.targetWatts)
        assertFalse(state.finished)
        assertEquals("Ready · Three by One", state.statusLine)
    }

    @Test
    fun `warm-up counts down and the first rep starts on second 300`() {
        val states = drive(SessionRunner(Session.threeByOne()), threeByOneRide())

        assertEquals(300, states[0].secondsLeft)
        assertEquals("Warm-up · 5:00 left", states[0].statusLine)
        assertNull(states[0].band)
        assertEquals(1, states[299].secondsLeft)
        assertEquals(WarmUp(300), states[299].block)

        val first = states[300]
        assertEquals(Rep(60, 250), first.block)
        assertEquals("rep1", first.blockTag)
        assertEquals(1, first.blockIndex)
        assertEquals(1, first.repNumber)
        assertEquals(250, first.targetWatts)
        assertEquals(0, first.secondsInBlock)
        assertEquals(60, first.secondsLeft)
        assertEquals(240, first.liveRepAverage)
        // 240 W is 4% under the 250 W target.
        assertEquals(Band.ORANGE, first.band)
        // Gear 12 at the start of the rep: no nag.
        assertFalse(first.gearNag)
        assertEquals("Rep 1/3 · 250 W · avg 240 W · 1:00 left", first.statusLine)
    }

    @Test
    fun `a rep is scored as the average of smoothed watts and published when it ends`() {
        val states = drive(SessionRunner(Session.threeByOne()), threeByOneRide())

        assertEquals(0, states[359].reps.size)
        assertEquals(250, states[359].liveRepAverage)

        val atEnd = states[360]
        assertEquals(Recovery(), atEnd.block)
        assertEquals("rec1", atEnd.blockTag)
        assertEquals(1, atEnd.reps.size)
        val rep1 = atEnd.reps[0]
        assertEquals(1, rep1.repNumber)
        assertEquals(250, rep1.targetWatts)
        assertEquals(60, rep1.seconds)
        assertEquals(250, rep1.averageWatts)
        assertEquals(Band.GREEN, rep1.band)
        assertEquals(12, rep1.gear)
        assertEquals(120, rep1.preHr)
        assertEquals(1.0, rep1.fractionOfTarget, 1e-9)
        assertNull(rep1.hrr60)
        assertNull(rep1.recoverySeconds)
        assertFalse(rep1.gateTimedOut)
    }

    @Test
    fun `recovery builds the peak for 30 s then waits for the gate`() {
        val states = drive(SessionRunner(Session.threeByOne()), threeByOneRide())

        val building = states[360]
        assertEquals(RecoveryPhase.BUILDING, building.recoveryPhase)
        assertEquals(1, building.repNumber)
        assertNull(building.secondsLeft)
        // Peak so far is this second's 180 (above the rep's 179): rise 60, gate 150.
        assertEquals(180, building.reps[0].peakHr)
        assertEquals(60, building.reps[0].rise)
        assertEquals(150, building.gateHr)
        assertEquals("Recovery 1/3 · Building · HR 180 · gate 150 · 0:00", building.statusLine)
        // By 370 the peak has reached 185, so the gate settles at 152.
        assertEquals(185, states[370].reps[0].peakHr)
        assertEquals(152, states[370].gateHr)

        assertEquals(RecoveryPhase.BUILDING, states[390].recoveryPhase)
        val recovering = states[400]
        assertEquals(RecoveryPhase.RECOVERING, recovering.recoveryPhase)
        assertEquals(152, recovering.gateHr)
        assertEquals("Recovery 1/3 · Recovering · HR 155 · gate 152 · 0:40", recovering.statusLine)
    }

    @Test
    fun `the gate does not open before 60 s even once HR is low enough`() {
        val states = drive(SessionRunner(Session.threeByOne()), threeByOneRide())

        // HR reached the gate at 43 s and is 16 bpm under it at 59 s; still in recovery.
        val last = states[419]
        assertEquals(Recovery(), last.block)
        assertEquals(59, last.secondsInBlock)
        assertNull(last.reps[0].recoverySeconds)

        val next = states[420]
        assertEquals(Rep(60, 250), next.block)
        assertEquals("rep2", next.blockTag)
        assertEquals(3, next.blockIndex)
        assertEquals(2, next.repNumber)
        assertEquals(0, next.secondsInBlock)
        val rep1 = next.reps[0]
        assertEquals(60, rep1.recoverySeconds)
        assertFalse(rep1.gateTimedOut)
        assertEquals(185, rep1.peakHr)
        // HR on the 60th second is the first reading of rep 2.
        assertEquals(120, rep1.hrAt60)
        assertEquals(65, rep1.hrr60)
        assertEquals(listOf(65), rep1.minuteDrops)
    }

    @Test
    fun `Three by One finishes after the cool-down with three results`() {
        val states = drive(SessionRunner(Session.threeByOne()), threeByOneRide())

        assertEquals(CoolDown(180), states[660].block)
        assertEquals("cooldown", states[660].blockTag)
        assertEquals(7, states[660].blockIndex)
        assertEquals(3, states[660].repNumber)
        assertEquals("Cool-down · 3:00 left", states[660].statusLine)
        assertEquals(1, states[839].secondsLeft)
        assertFalse(states[839].finished)

        val done = states[840]
        assertTrue(done.finished)
        assertNull(done.block)
        assertNull(done.blockTag)
        assertEquals(8, done.blockIndex)
        assertEquals(listOf(1, 2, 3), done.reps.map { it.repNumber })
        assertEquals(listOf(250, 250, 250), done.reps.map { it.averageWatts })
        assertEquals(listOf(60, 60, 60), done.reps.map { it.recoverySeconds })
        // The 60th second of recoveries 1 and 2 is the next rep's first reading (HR 120);
        // for recovery 3 it is the cool-down's (HR 110).
        assertEquals(listOf(65, 65, 75), done.reps.map { it.hrr60 })
        assertEquals(listOf(false, false, false), done.reps.map { it.gateTimedOut })
        assertEquals("Session complete · 250 / 250 / 250 W", done.statusLine)
    }

    @Test
    fun `seconds after the session has finished are ignored`() {
        val runner = SessionRunner(Session.threeByOne())
        val states = drive(runner, threeByOneRide())
        val done = states.last()

        runner.onSecond(841, 300, 150, 9)
        runner.onSecond(842, 300, 150, 9)
        assertEquals(done, runner.state.value)
    }

    // ---- Smaller sessions for the edge cases ------------------------------------------------
    //
    // Timeline: warm-up 0..9; rep 10..29; recovery from 30.

    private fun short(gate: HrGate = HrGate()) = Session(
        "Short",
        listOf(WarmUp(10), Rep(20, 200), Recovery(gate), CoolDown(10)),
    )

    private fun shortRide(repTick: (Int) -> Tick, recoveryTick: (Int) -> Tick, recoverySeconds: Int) =
        List(10) { Tick(80, 100) } + List(20, repTick) + List(recoverySeconds, recoveryTick) + List(20) { Tick(80, 100) }

    @Test
    fun `missing power counts as zero in the rep average`() {
        val ride = shortRide(
            repTick = { i -> Tick(if (i < 10) 200 else null, 140) },
            recoveryTick = { Tick(80, 100) },
            recoverySeconds = 120,
        )
        val states = drive(SessionRunner(short()), ride)
        assertEquals(100, states[30].reps[0].averageWatts)
        assertEquals(Band.RED, states[30].reps[0].band)
    }

    @Test
    fun `the rep's gear is the one held longest`() {
        val ride = shortRide(
            repTick = { i -> Tick(200, 140, if (i < 8) 10 else 12) },
            recoveryTick = { Tick(80, 100) },
            recoverySeconds = 120,
        )
        val states = drive(SessionRunner(short()), ride)
        assertEquals(12, states[30].reps[0].gear)
    }

    @Test
    fun `peak HR is taken up to 30 s after the rep and no later`() {
        // HR 150 through the rep, climbing to 180 at 30 s after it, a spurious 199 at 31 s,
        // then falling 1 bpm a second from 180. Rise 30, gate 165, reached at 46 s.
        val ride = shortRide(
            repTick = { Tick(200, 150) },
            recoveryTick = { s ->
                val hr = when {
                    s <= 30 -> 150 + s
                    s == 31 -> 199
                    else -> 180 - (s - 31)
                }
                Tick(80, hr)
            },
            recoverySeconds = 61,
        )
        val states = drive(SessionRunner(short()), ride)

        assertEquals(150, states[30].reps[0].peakHr)
        assertEquals(180, states[60].reps[0].peakHr)
        assertEquals(180, states[61].reps[0].peakHr)
        assertEquals(165, states[61].gateHr)
        assertEquals(Recovery(), states[89].block)
        assertEquals(CoolDown(10), states[90].block)
        val rep = states[90].reps[0]
        assertEquals(180, rep.peakHr)
        assertEquals(60, rep.recoverySeconds)
        assertEquals(151, rep.hrAt60)
        assertEquals(29, rep.hrr60)
        assertEquals(listOf(29), rep.minuteDrops)
    }

    @Test
    fun `the gate releases once half the rise is recovered, after the minimum`() {
        // Pre-rep HR 120, peak 170 held for 5 s, so the gate is 145. HR then falls 1 bpm
        // every 3 s: 146 at 79 s, 145 at 80 s.
        val ride = shortRide(
            repTick = { i -> Tick(200, 120 + 2 * i) },
            recoveryTick = { s -> Tick(80, if (s <= 5) 170 else 170 - (s - 5) / 3) },
            recoverySeconds = 81,
        )
        val states = drive(SessionRunner(short()), ride)

        assertEquals(120, states[30].reps[0].preHr)
        assertEquals(170, states[40].reps[0].peakHr)
        assertEquals(145, states[40].gateHr)
        // Past the minimum but HR still 152: closed.
        assertEquals(Recovery(), states[90].block)
        assertEquals(RecoveryPhase.RECOVERING, states[90].recoveryPhase)
        assertEquals(listOf(18), states[90].reps[0].minuteDrops)
        assertEquals(Recovery(), states[109].block)
        assertEquals(CoolDown(10), states[110].block)
        val rep = states[110].reps[0]
        assertEquals(80, rep.recoverySeconds)
        assertFalse(rep.gateTimedOut)
    }

    @Test
    fun `per-minute drops stamp as each minute completes`() {
        // Pre 120, peak 180, gate 150. HR falls 1 bpm every 4 s: 165 at 60 s, 150 at 120 s.
        val ride = shortRide(
            repTick = { Tick(200, 120) },
            recoveryTick = { s -> Tick(80, 180 - s / 4) },
            recoverySeconds = 121,
        )
        val states = drive(SessionRunner(short()), ride)

        assertEquals(emptyList<Int>(), states[89].reps[0].minuteDrops)
        assertEquals(listOf(15), states[90].reps[0].minuteDrops)
        assertEquals(listOf(15), states[149].reps[0].minuteDrops)
        assertEquals(Recovery(), states[149].block)
        assertEquals(CoolDown(10), states[150].block)
        val rep = states[150].reps[0]
        assertEquals(listOf(15, 15), rep.minuteDrops)
        assertEquals(120, rep.recoverySeconds)
    }

    @Test
    fun `the gate times out at its maximum when HR never comes down`() {
        // Pre 120, peak 170, gate 145; HR sits at 170 throughout.
        val gate = HrGate(maxSeconds = 90)
        val ride = shortRide(
            repTick = { Tick(200, 120) },
            recoveryTick = { Tick(80, 170) },
            recoverySeconds = 91,
        )
        val states = drive(SessionRunner(short(gate)), ride)

        assertEquals(Recovery(gate), states[119].block)
        assertEquals(CoolDown(10), states[120].block)
        val rep = states[120].reps[0]
        assertEquals(90, rep.recoverySeconds)
        assertTrue(rep.gateTimedOut)
        assertEquals(170, rep.peakHr)
        assertEquals(145, rep.gateHr)
        assertEquals(0, rep.hrr60)
    }

    @Test
    fun `without a strap the gate falls back to a fixed length`() {
        val gate = HrGate(fallbackSeconds = 45)
        val ride = shortRide(
            repTick = { Tick(200, null) },
            recoveryTick = { Tick(80, null) },
            recoverySeconds = 45,
        )
        val states = drive(SessionRunner(short(gate)), ride)

        assertNull(states[30].reps[0].peakHr)
        assertNull(states[30].gateHr)
        assertEquals(Recovery(gate), states[74].block)
        assertEquals(CoolDown(10), states[75].block)
        val rep = states[75].reps[0]
        assertEquals(45, rep.recoverySeconds)
        assertFalse(rep.gateTimedOut)
        assertNull(rep.preHr)
        assertNull(rep.peakHr)
        assertNull(rep.gateHr)
        assertNull(rep.hrr60)
        assertEquals(emptyList<Int>(), rep.minuteDrops)
    }

    @Test
    fun `HR lost for more than 10 s during recovery falls back and says so`() {
        // Strap works through the rep and the first 20 s of recovery, then goes quiet.
        val ride = shortRide(
            repTick = { Tick(200, 160) },
            recoveryTick = { s -> Tick(80, if (s < 20) 165 else null) },
            recoverySeconds = 120,
        )
        val states = drive(SessionRunner(short()), ride)

        assertEquals("Recovery 1/1 · Recovering · no HR · 1:00", states[90].statusLine)
        assertNull(states[90].gateHr)
        assertEquals(Recovery(), states[149].block)
        assertEquals(CoolDown(10), states[150].block)
        val rep = states[150].reps[0]
        assertEquals(120, rep.recoverySeconds)
        assertFalse(rep.gateTimedOut)
        assertEquals(165, rep.peakHr)
        // No reading to take at 60 s, so no HRR60 and no minute drop.
        assertNull(rep.hrr60)
        assertEquals(emptyList<Int>(), rep.minuteDrops)
    }

    @Test
    fun `a short HR gap is tolerated`() {
        // 10 s of nothing from 20 s to 29 s, then readings resume well below the gate (162).
        val ride = shortRide(
            repTick = { Tick(200, 160) },
            recoveryTick = { s -> Tick(80, if (s in 20..29) null else if (s < 20) 165 else 120) },
            recoverySeconds = 60,
        )
        val states = drive(SessionRunner(short()), ride)

        assertEquals(162, states[89].gateHr)
        assertEquals(CoolDown(10), states[90].block)
        assertEquals(60, states[90].reps[0].recoverySeconds)
    }

    @Test
    fun `a gap in the ride clock advances past a whole block`() {
        val runner = SessionRunner(short())
        runner.onSecond(0, 80, 100, 7)
        // Nothing for 12 s, then a reading 2 s into the rep.
        runner.onSecond(12, 210, 150, 7)

        val state = runner.state.value
        assertEquals(Rep(20, 200), state.block)
        assertEquals(2, state.secondsInBlock)
        assertEquals(18, state.secondsLeft)
        assertEquals(210, state.liveRepAverage)
    }

    // ---- Gear nag ----------------------------------------------------------------------------
    //
    // The short session again: warm-up 0..9, rep 10..29. The nag depends only on the gear
    // on the rep's first second.

    private fun nagRide(gearAtStart: Int?, gearLater: Int? = gearAtStart) =
        List(10) { Tick(80, null, 7) } +
            List(20) { i -> Tick(200, null, if (i == 0) gearAtStart else gearLater) } +
            List(130) { Tick(80, null, gearLater) }

    @Test
    fun `gear nag shows for the first 5 s of a rep started below gear 10`() {
        val states = drive(SessionRunner(short()), nagRide(gearAtStart = 9))

        assertFalse(states[9].gearNag)
        assertTrue(states[10].gearNag)
        assertTrue(states[14].gearNag)
        assertFalse(states[15].gearNag)
        assertFalse(states[30].gearNag)
    }

    @Test
    fun `gear nag stays off from gear 10 up`() {
        assertFalse(drive(SessionRunner(short()), nagRide(gearAtStart = 10))[10].gearNag)
        assertFalse(drive(SessionRunner(short()), nagRide(gearAtStart = 14))[12].gearNag)
    }

    @Test
    fun `gear nag fires when the gear at rep start is unknown`() {
        val states = drive(SessionRunner(short()), nagRide(gearAtStart = null, gearLater = 12))
        assertTrue(states[10].gearNag)
        assertTrue(states[12].gearNag)
        assertFalse(states[15].gearNag)
    }

    @Test
    fun `gear nag is judged on the starting gear even if the rider shifts straight away`() {
        val states = drive(SessionRunner(short()), nagRide(gearAtStart = 8, gearLater = 12))
        assertTrue(states[13].gearNag)
    }
}
