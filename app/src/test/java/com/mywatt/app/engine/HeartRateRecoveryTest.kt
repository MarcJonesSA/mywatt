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

    @Test
    fun `measures one recovery after a sustained effort`() {
        val engine = HeartRateRecovery()
        val results = run(engine, easy(60) + effort(70) + recovery(90))

        assertEquals(1, results.size)
        val (second, result) = results.single()

        // Power drops at t=130; the end is confirmed after 5 low seconds, at t=134; the
        // window closes 60 s later at t=194.
        assertEquals(194L, second)
        assertEquals(194L, result.elapsedAtEnd)
        assertEquals(162, result.peakHr)
        // 64 s into the decline HR has hit the 70 bpm floor.
        assertEquals(70, result.hrAt60)
        assertEquals(92, result.hrr60)
        assertEquals(result, engine.latest.value)
    }

    @Test
    fun `an effort shorter than 30 s produces no result`() {
        val engine = HeartRateRecovery()
        val results = run(engine, easy(60) + effort(25) + recovery(90))

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
    }

    @Test
    fun `more than 10 s of missing HR abandons the window`() {
        val engine = HeartRateRecovery()
        val gap = List(11) { Tick(null, 85) }
        val results = run(engine, easy(60) + effort(70) + recovery(10) + gap + recovery(60))

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
    fun `reset keeps the last result`() {
        val engine = HeartRateRecovery()
        run(engine, easy(60) + effort(70) + recovery(90))
        val before = engine.latest.value
        engine.reset()
        assertEquals(before, engine.latest.value)
    }
}
