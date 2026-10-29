package com.mywatt.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.max
import kotlin.math.roundToInt

class HeartRateRecoveryTest {

    /** One second of synthetic ride data. */
    private data class Tick(val hr: Int?, val watts: Int)

    /** Feeds every tick in order and returns the results emitted, with the second they landed on. */
    private fun run(engine: HeartRateRecovery, ride: List<Tick>): List<Pair<Long, RecoveryResult>> {
        val results = ArrayList<Pair<Long, RecoveryResult>>()
        ride.forEachIndexed { index, tick ->
            engine.onSecond(index.toLong(), tick.hr, tick.watts)?.let { results += index.toLong() to it }
        }
        return results
    }

    /** Easy riding: 90 W at HR 100. */
    private fun easy(seconds: Int) = List(seconds) { Tick(100, 90) }

    /** A hard effort at 275 W with HR climbing linearly from 100 to 162. */
    private fun effort(seconds: Int) = List(seconds) { i ->
        Tick(100 + ((i + 1) * 62.0 / seconds).roundToInt(), 275)
    }

    /** Recovery at 85 W with HR falling 162, 160, 158... down to a floor of 70. */
    private fun recovery(seconds: Int, hrFloor: Int = 70) = List(seconds) { i ->
        Tick(max(hrFloor, 162 - 2 * i), 85)
    }

    // Power drops at t=130 in every ride below; the end is confirmed after 5 low seconds,
    // at t=134; the 60 s reading lands at t=194 and the 120 s reading at t=254.
    private val effortEnd = 134L

    @Test
    fun `measures 60 s and 120 s recovery after a sustained effort`() {
        val engine = HeartRateRecovery()
        val results = run(engine, easy(60) + effort(70) + recovery(130))

        assertEquals(2, results.size)
        val (second60, at60) = results[0]
        val (second120, at120) = results[1]

        assertEquals(194L, second60)
        assertEquals(effortEnd, at60.elapsedAtEnd)
        assertEquals(162, at60.peakHr)
        // 64 s into the decline HR has hit the 70 bpm floor.
        assertEquals(70, at60.hrAt60)
        assertEquals(92, at60.hrr60)
        assertNull(at60.hrr120)
        assertNull(at60.hrAt120)

        assertEquals(254L, second120)
        assertEquals(at60.copy(hrAt120 = 70, hrr120 = 92), at120)
        assertEquals(at120, engine.latest.value)
        assertNull(engine.progress.value)
    }

    @Test
    fun `peak is taken from up to 30 s after the effort ends`() {
        val engine = HeartRateRecovery()
        // After the legs stop at t=130 HR keeps climbing 1 bpm a second to 171 at t=154,
        // 20 s after the confirmed end, then falls 1 bpm a second. A spike to 190 at t=170,
        // 36 s after the end, is outside the peak window and must be ignored.
        val late = List(130) { i ->
            val hr = when {
                i == 40 -> 190
                i < 24 -> 147 + i
                else -> max(70, 171 - (i - 24))
            }
            Tick(hr, 85)
        }
        val results = run(engine, easy(60) + effort(70) + late)

        assertEquals(2, results.size)
        val at120 = results[1].second
        assertEquals(171, at120.peakHr)
        assertEquals(131, at120.hrAt60)
        assertEquals(40, at120.hrr60)
        assertEquals(71, at120.hrAt120)
        assertEquals(100, at120.hrr120)
    }

    @Test
    fun `an effort shorter than 30 s produces no result`() {
        val engine = HeartRateRecovery()
        val results = run(engine, easy(60) + effort(25) + recovery(130))

        assertEquals(0, results.size)
        assertNull(engine.latest.value)
    }

    @Test
    fun `a new effort 40 s into the window abandons it`() {
        val engine = HeartRateRecovery()
        // Window opens at t=134; the rider goes hard again at t=174, 40 s in, and holds it.
        val results = run(engine, easy(60) + effort(70) + recovery(44) + effort(40))

        assertEquals(0, results.size)
        assertNull(engine.latest.value)
        assertNull(engine.progress.value)
    }

    @Test
    fun `a new effort after 60 s keeps the 60 s reading but never gives a 120 s one`() {
        val engine = HeartRateRecovery()
        // Hard again at t=214, 80 s into the window, long enough to be detected.
        val results = run(engine, easy(60) + effort(70) + recovery(84) + effort(70))

        assertEquals(1, results.size)
        val latest = engine.latest.value
        assertNotNull(latest)
        assertEquals(92, latest!!.hrr60)
        assertNull(latest.hrr120)
    }

    @Test
    fun `more than 10 s of missing HR abandons the window`() {
        val engine = HeartRateRecovery()
        val gap = List(11) { Tick(null, 85) }
        val results = run(engine, easy(60) + effort(70) + recovery(10) + gap + recovery(120))

        assertEquals(0, results.size)
        assertNull(engine.latest.value)
    }

    @Test
    fun `a short HR gap is tolerated and the last reading stands in at 60 s`() {
        val engine = HeartRateRecovery()
        val gap = List(10) { Tick(null, 85) }
        val results = run(engine, easy(60) + effort(70) + recovery(50) + gap + recovery(20))

        assertEquals(1, results.size)
        assertNotNull(engine.latest.value)
    }

    @Test
    fun `progress counts the seconds since the effort ended`() {
        val engine = HeartRateRecovery()
        run(engine, easy(60) + effort(70) + recovery(30))

        // Last tick is t=159, 25 s after the end at t=134, with HR 162 - 2*29.
        assertEquals(RecoveryProgress(25, 104), engine.progress.value)
    }

    @Test
    fun `reset keeps the last result`() {
        val engine = HeartRateRecovery()
        run(engine, easy(60) + effort(70) + recovery(130))
        val before = engine.latest.value
        engine.reset()
        assertEquals(before, engine.latest.value)
        assertNull(engine.progress.value)
    }
}
