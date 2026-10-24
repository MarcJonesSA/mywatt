package com.mywatt.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerSmootherTest {

    @Test
    fun `averages the samples inside the window`() {
        val smoother = PowerSmoother(windowSeconds = 3)
        smoother.add(200, atMs = 1_000)
        smoother.add(220, atMs = 2_000)
        smoother.add(240, atMs = 3_000)

        assertEquals(220, smoother.average(nowMs = 3_000))
    }

    @Test
    fun `drops samples that have fallen out of the window`() {
        val smoother = PowerSmoother(windowSeconds = 3)
        smoother.add(100, atMs = 1_000)
        smoother.add(200, atMs = 2_000)
        smoother.add(300, atMs = 3_000)
        smoother.add(400, atMs = 4_000)

        // At t=4s the 3 s window covers (1s, 4s], so the 100 W sample is gone.
        assertEquals(300, smoother.average(nowMs = 4_000))
    }

    @Test
    fun `returns no reading when the window is empty`() {
        val smoother = PowerSmoother(windowSeconds = 3)
        assertNull(smoother.average(nowMs = 0))

        smoother.add(250, atMs = 1_000)
        assertNull(smoother.average(nowMs = 10_000))
    }

    @Test
    fun `rounds to whole watts`() {
        val smoother = PowerSmoother(windowSeconds = 3)
        smoother.add(200, atMs = 1_000)
        smoother.add(201, atMs = 2_000)

        assertEquals(201, smoother.average(nowMs = 2_000)) // 200.5 rounds up
    }

    @Test
    fun `clamps the window to the allowed range`() {
        assertEquals(RiderSettings.MIN_SMOOTHING_SECONDS, PowerSmoother(0).windowSeconds)
        assertEquals(RiderSettings.MAX_SMOOTHING_SECONDS, PowerSmoother(99).windowSeconds)

        val smoother = PowerSmoother(3)
        smoother.windowSeconds = 1
        smoother.add(100, atMs = 1_000)
        smoother.add(300, atMs = 2_000)
        assertEquals(300, smoother.average(nowMs = 2_000))
    }

    @Test
    fun `clear forgets everything`() {
        val smoother = PowerSmoother(3)
        smoother.add(100, atMs = 1_000)
        smoother.clear()
        assertNull(smoother.average(nowMs = 1_000))
    }

    @Test
    fun `defaults to a two second window`() {
        assertEquals(2, PowerSmoother().windowSeconds)
        assertEquals(2, RiderSettings.DEFAULT_SMOOTHING_SECONDS)
    }

    // ------------------------------------------------------------ dropouts

    @Test
    fun `ignores a lone dropout while pedalling`() {
        val smoother = PowerSmoother(windowSeconds = 5)
        smoother.add(250, atMs = 1_000, cadenceRpm = 90.0)
        smoother.add(10, atMs = 2_000, cadenceRpm = 90.0) // BLE dropout
        // While the dip is held back the output does not move on it.
        assertEquals(250, smoother.average(nowMs = 2_000))

        smoother.add(250, atMs = 3_000, cadenceRpm = 90.0)
        // 10 W was below 20% of both 250 W neighbours, so it never entered the window.
        assertEquals(250, smoother.average(nowMs = 3_000))
    }

    @Test
    fun `accepts two consecutive low samples as real`() {
        val smoother = PowerSmoother(windowSeconds = 5)
        smoother.add(250, atMs = 1_000, cadenceRpm = 90.0)
        smoother.add(10, atMs = 2_000, cadenceRpm = 90.0)
        smoother.add(10, atMs = 3_000, cadenceRpm = 90.0)

        // (250 + 10 + 10) / 3 = 90
        assertEquals(90, smoother.average(nowMs = 3_000))
    }

    @Test
    fun `accepts a low sample at zero cadence`() {
        val smoother = PowerSmoother(windowSeconds = 5)
        smoother.add(250, atMs = 1_000, cadenceRpm = 90.0)
        smoother.add(10, atMs = 2_000, cadenceRpm = 0.0) // rider has stopped pedalling

        // (250 + 10) / 2 = 130, counted straight away.
        assertEquals(130, smoother.average(nowMs = 2_000))
    }

    @Test
    fun `accepts a dip that is not far below its next neighbour`() {
        val smoother = PowerSmoother(windowSeconds = 5)
        smoother.add(250, atMs = 1_000, cadenceRpm = 90.0)
        smoother.add(40, atMs = 2_000, cadenceRpm = 90.0)  // held back
        smoother.add(100, atMs = 3_000, cadenceRpm = 90.0) // 40 is 40% of this, so real

        // (250 + 40 + 100) / 3 = 130
        assertEquals(130, smoother.average(nowMs = 3_000))
    }

    @Test
    fun `does not treat a low sample as a dropout without cadence`() {
        val smoother = PowerSmoother(windowSeconds = 5)
        smoother.add(250, atMs = 1_000)
        smoother.add(10, atMs = 2_000)

        assertEquals(130, smoother.average(nowMs = 2_000))
    }
}
