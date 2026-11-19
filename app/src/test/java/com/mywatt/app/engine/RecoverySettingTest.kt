package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverySettingTest {

    @Test
    fun `the four settings carry their fraction, minimum and timeout`() {
        assertEquals(listOf(0.50, 0.60, 0.70, 0.80), RecoverySetting.entries.map { it.releaseFraction })
        assertEquals(listOf(60, 45, 30, 30), RecoverySetting.entries.map { it.minRecoverySeconds })
        assertEquals(listOf(240, 240, 240, 240), RecoverySetting.entries.map { it.timeoutSeconds })
        assertEquals(RecoverySetting.Building, RecoverySetting.DEFAULT)
        assertEquals(RecoverySetting.Trained, RecoverySetting.fromName("Trained"))
        assertEquals(RecoverySetting.Building, RecoverySetting.fromName("nonsense"))
        assertEquals(RecoverySetting.Building, RecoverySetting.fromName(null))
    }

    @Test
    fun `the gate takes its rule from the setting`() {
        val gate = HrGate(RecoverySetting.Intermediate)
        assertEquals(0.60, gate.recoveredFraction, 1e-9)
        assertEquals(45, gate.minSeconds)
        assertEquals(240, gate.maxSeconds)
        // Pre-rep 100, peak 160: rise 60, so recover 36 bpm, gate 124.
        assertEquals(124, gate.thresholdHr(100, 160))
        assertEquals(130, HrGate(RecoverySetting.Building).thresholdHr(100, 160))
        assertEquals(118, HrGate(RecoverySetting.Trained).thresholdHr(100, 160))
        assertEquals(112, HrGate(RecoverySetting.Peak).thresholdHr(100, 160))
    }

    // Warm-up 0..4, rep 5..64, recovery from second 65.
    private fun session(setting: RecoverySetting) = Session(
        "Gate",
        listOf(WarmUp(5), Rep(60, 200), Recovery(HrGate(setting)), CoolDown(10)),
        setting,
    )

    /** HR 100 on the rep's first second, rising to a 160 peak on its last: rise 60. */
    private fun rideRep(runner: SessionRunner) {
        repeat(5) { s -> runner.onSecond(s.toLong(), 100, 100, 12) }
        repeat(60) { i -> runner.onSecond(5L + i, 200, if (i == 59) 160 else 100 + i, 12) }
    }

    /** Rides the recovery with [hrAt] (seconds since the rep ended) until the gate opens. */
    private fun rideRecovery(runner: SessionRunner, hrAt: (Int) -> Int): RepResult {
        var s = 0
        while (runner.state.value.block is Recovery || s == 0) {
            runner.onSecond(65L + s, 90, hrAt(s), 7)
            s++
            check(s < 400)
        }
        return runner.state.value.reps.single()
    }

    @Test
    fun `each setting releases when its fraction of the rise is recovered`() {
        // HR falls 1 bpm every 4 s from the 160 peak, so it reaches gate G at 4 * (160 - G) s,
        // later than every minimum: 130 at 120 s, 124 at 144 s, 118 at 168 s, 112 at 192 s.
        val expected = mapOf(
            RecoverySetting.Building to 120,
            RecoverySetting.Intermediate to 144,
            RecoverySetting.Trained to 168,
            RecoverySetting.Peak to 192,
        )
        expected.forEach { (setting, seconds) ->
            val runner = SessionRunner(session(setting))
            rideRep(runner)
            val rep = rideRecovery(runner) { s -> 160 - s / 4 }
            assertEquals("$setting", seconds, rep.recoverySeconds)
            assertFalse("$setting", rep.gateTimedOut)
            assertEquals("$setting", HrGate(setting).thresholdHr(100, 160), rep.gateHr)
        }
    }

    @Test
    fun `no setting releases before its minimum even when HR is already down`() {
        RecoverySetting.entries.forEach { setting ->
            val runner = SessionRunner(session(setting))
            rideRep(runner)
            // HR drops straight back to the pre-rep 100 a second after the rep.
            var s = 0
            while (s < setting.minRecoverySeconds) {
                runner.onSecond(65L + s, 90, if (s == 0) 160 else 100, 7)
                assertTrue("$setting still recovering at $s s", runner.state.value.block is Recovery)
                s++
            }
            runner.onSecond(65L + s, 90, 100, 7)
            assertTrue("$setting released at its minimum", runner.state.value.block is CoolDown)
            assertEquals("$setting", setting.minRecoverySeconds, runner.state.value.reps.single().recoverySeconds)
        }
    }

    @Test
    fun `the result records the setting the session used`() {
        val controller = SessionController(session(RecoverySetting.Peak))
        controller.end()
        assertEquals(RecoverySetting.Peak, controller.result.value!!.recoverySetting)
        assertEquals(RecoverySetting.Trained, Session.threeByOne(250, RecoverySetting.Trained).recoverySetting)
        assertEquals(
            setOf(HrGate(RecoverySetting.Trained)),
            Session.threeByOne(250, RecoverySetting.Trained).blocks.filterIsInstance<Recovery>().map { it.gate }.toSet(),
        )
    }

    @Test
    fun `settings rows read in rider language`() {
        assertEquals(
            listOf(
                "Fullest recoveries. Your first weeks back.",
                "Solid recoveries. Training regularly.",
                "Shorter recoveries. Fit and consistent.",
                "Race-tight recoveries. Peak weeks only.",
            ),
            RecoverySetting.entries.map { it.blurb },
        )
        RecoverySetting.entries.forEach { assertFalse(it.blurb.contains('%')) }
    }
}
