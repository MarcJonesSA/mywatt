package com.mywatt.app.data

import kotlin.math.roundToInt

/**
 * Time-based rolling average of power.
 *
 * Samples are stamped with the clock they arrived on and anything older than the window is
 * dropped, so the average always covers "the last N seconds" however often the trainer
 * notifies. The rev counter needle, the main watts number and any future target comparison
 * read the smoothed value; the raw reading stays available on the live sample.
 *
 * Dropout rejection: a lone sample below 20% of both its neighbours (the previous accepted
 * sample and the next one) while cadence is above 30 rpm is a BLE glitch, not a real power
 * drop, and is never added to the window. Because that needs the *next* sample, a suspect
 * reading is held back until the following one arrives; two or more consecutive low samples
 * are real and are accepted. Raw watts are unaffected; only this average skips the dropout.
 *
 * Cadence is deliberately not smoothed; it is only used here to tell a dropout from a stop.
 */
class PowerSmoother(windowSeconds: Int = RiderSettings.DEFAULT_SMOOTHING_SECONDS) {

    private class Sample(val atMs: Long, val watts: Int)

    private val samples = ArrayDeque<Sample>()

    /** The most recently accepted sample, kept even after it leaves the window. */
    private var lastAccepted: Sample? = null

    /** A suspiciously low sample waiting for the next one to decide whether it was real. */
    private var pending: Sample? = null

    /** Window length in seconds, clamped to the range the settings allow. */
    var windowSeconds: Int = clampWindow(windowSeconds)
        set(value) {
            field = clampWindow(value)
        }

    private val windowMs: Long get() = windowSeconds * 1_000L

    /**
     * Records a raw power reading taken at [atMs] (SystemClock.elapsedRealtime or similar).
     * [cadenceRpm] is the raw cadence from the same notification; null means unknown, which
     * disables dropout rejection for this sample.
     */
    fun add(watts: Int, atMs: Long, cadenceRpm: Double? = null) {
        val sample = Sample(atMs, watts)

        // Resolve the held-back sample first: a lone dip is discarded, anything else is real.
        pending?.let { held ->
            if (!isFarBelow(held.watts, sample.watts)) accept(held)
            pending = null
        }

        val previous = lastAccepted
        val suspect = previous != null &&
            isFarBelow(sample.watts, previous.watts) &&
            cadenceRpm != null && cadenceRpm > DROPOUT_MIN_CADENCE_RPM
        if (suspect) {
            pending = sample
        } else {
            accept(sample)
        }
        prune(atMs)
    }

    /**
     * The mean of every sample inside the window ending at [nowMs], rounded to whole watts,
     * or null when no sample falls inside it. A held-back suspect sample is not counted.
     */
    fun average(nowMs: Long): Int? {
        prune(nowMs)
        if (samples.isEmpty()) return null
        return (samples.sumOf { it.watts }.toDouble() / samples.size).roundToInt()
    }

    fun clear() {
        samples.clear()
        lastAccepted = null
        pending = null
    }

    private fun accept(sample: Sample) {
        samples.addLast(sample)
        lastAccepted = sample
    }

    /** True when [value] is below [DROPOUT_FRACTION] of [reference]. */
    private fun isFarBelow(value: Int, reference: Int): Boolean =
        value < reference * DROPOUT_FRACTION

    private fun prune(nowMs: Long) {
        val oldest = nowMs - windowMs
        while (samples.isNotEmpty() && samples.first().atMs <= oldest) samples.removeFirst()
    }

    private fun clampWindow(seconds: Int): Int =
        seconds.coerceIn(RiderSettings.MIN_SMOOTHING_SECONDS, RiderSettings.MAX_SMOOTHING_SECONDS)

    companion object {
        /** A sample under this fraction of both neighbours is a candidate dropout. */
        private const val DROPOUT_FRACTION = 0.2

        /** Below this cadence the rider may genuinely have stopped, so lows are believed. */
        private const val DROPOUT_MIN_CADENCE_RPM = 30.0
    }
}
