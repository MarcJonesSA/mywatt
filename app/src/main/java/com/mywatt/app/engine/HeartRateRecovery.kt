package com.mywatt.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One completed 60-second recovery window. [hrr60] is how far HR fell: peak minus HR at 60 s. */
data class RecoveryResult(
    val peakHr: Int,
    val hrAt60: Int,
    val hrr60: Int,
    /** Ride clock, in seconds, when the window completed. */
    val elapsedAtEnd: Long,
)

/**
 * 60-second heart-rate recovery (HRR60) after an effort. Pure Kotlin; drive it with
 * [onSecond] once a second.
 *
 * Effort detection: the rider is in an effort while the 30 s rolling average of smoothed
 * power is at least [EFFORT_THRESHOLD_WATTS]. The effort ends when smoothed power falls
 * below 60% of that average (frozen at the moment of the drop) and stays there for
 * [END_CONFIRM_SECONDS]. Only efforts that lasted at least [MIN_EFFORT_SECONDS] from
 * detection to end count; because detection trails the real start by roughly ten seconds,
 * that is about 35 s of real work.
 *
 * At effort end the peak HR over the last [PEAK_LOOKBACK_SECONDS] is taken and a 60 s
 * window opened. When it closes, hrr60 = peak minus the HR at 60 s (the most recent reading
 * if that exact second has none). The window is abandoned if more than [MAX_HR_GAP_SECONDS]
 * of HR readings are missing, or if a new effort starts first. The power buffer is cleared
 * when an effort ends so that a new effort during recovery is detected against the easy
 * riding, not against the effort just finished.
 *
 * TODO: EFFORT_THRESHOLD_WATTS is fixed at 150 W; it should come from FTP later.
 */
class HeartRateRecovery {

    private enum class Phase { IDLE, IN_EFFORT, ENDING, RECOVERING }

    private class HrSample(val atSeconds: Long, val bpm: Int)

    private val _latest = MutableStateFlow<RecoveryResult?>(null)

    /** The latest completed window, or null until one completes. Kept until replaced. */
    val latest: StateFlow<RecoveryResult?> = _latest.asStateFlow()

    private var phase = Phase.IDLE
    private val powerWindow = ArrayDeque<Int>()
    private val hrHistory = ArrayDeque<HrSample>()

    private var effortStartSeconds = 0L
    private var endReferenceAverage = 0.0
    private var belowCount = 0

    private var windowStartSeconds = 0L
    private var windowPeakHr = 0
    private var windowMissingHr = 0
    private var windowLastHr: Int? = null

    /**
     * Feeds one second of ride data. Returns the result if a window completed on this very
     * second (for the CSV), else null. [smoothedWatts] null counts as 0 W.
     */
    fun onSecond(elapsedSeconds: Long, hrBpm: Int?, smoothedWatts: Int?): RecoveryResult? {
        val watts = smoothedWatts ?: 0
        if (hrBpm != null) hrHistory.addLast(HrSample(elapsedSeconds, hrBpm))
        while (hrHistory.isNotEmpty() &&
            hrHistory.first().atSeconds <= elapsedSeconds - PEAK_LOOKBACK_SECONDS
        ) {
            hrHistory.removeFirst()
        }

        powerWindow.addLast(watts)
        while (powerWindow.size > AVERAGE_WINDOW_SECONDS) powerWindow.removeFirst()
        val average = powerWindow.average()
        val inEffortNow = average >= EFFORT_THRESHOLD_WATTS

        return when (phase) {
            Phase.IDLE -> {
                if (inEffortNow) beginEffort(elapsedSeconds)
                null
            }

            Phase.IN_EFFORT -> {
                when {
                    watts < END_FRACTION * average -> {
                        phase = Phase.ENDING
                        endReferenceAverage = average
                        belowCount = 1
                        null
                    }
                    !inEffortNow -> {
                        // Faded out gradually rather than stopping: no clean end to measure.
                        phase = Phase.IDLE
                        null
                    }
                    else -> null
                }
            }

            Phase.ENDING -> {
                if (watts < END_FRACTION * endReferenceAverage) {
                    belowCount++
                    if (belowCount >= END_CONFIRM_SECONDS) endEffort(elapsedSeconds) else null
                } else {
                    phase = if (inEffortNow) Phase.IN_EFFORT else Phase.IDLE
                    null
                }
            }

            Phase.RECOVERING -> {
                if (hrBpm == null) windowMissingHr++ else windowLastHr = hrBpm
                when {
                    windowMissingHr > MAX_HR_GAP_SECONDS -> {
                        phase = Phase.IDLE
                        null
                    }
                    inEffortNow -> {
                        beginEffort(elapsedSeconds)
                        null
                    }
                    elapsedSeconds - windowStartSeconds >= WINDOW_SECONDS -> completeWindow(elapsedSeconds)
                    else -> null
                }
            }
        }
    }

    /** Forgets the ride in progress but keeps [latest], for a fresh connection. */
    fun reset() {
        phase = Phase.IDLE
        powerWindow.clear()
        hrHistory.clear()
        belowCount = 0
        windowMissingHr = 0
        windowLastHr = null
    }

    private fun beginEffort(elapsedSeconds: Long) {
        phase = Phase.IN_EFFORT
        effortStartSeconds = elapsedSeconds
    }

    private fun endEffort(elapsedSeconds: Long): RecoveryResult? {
        powerWindow.clear()
        val peak = hrHistory.maxOfOrNull { it.bpm }
        if (elapsedSeconds - effortStartSeconds < MIN_EFFORT_SECONDS || peak == null) {
            phase = Phase.IDLE
            return null
        }
        phase = Phase.RECOVERING
        windowStartSeconds = elapsedSeconds
        windowPeakHr = peak
        windowMissingHr = 0
        windowLastHr = null
        return null
    }

    private fun completeWindow(elapsedSeconds: Long): RecoveryResult? {
        phase = Phase.IDLE
        val hrAt60 = windowLastHr ?: return null
        val result = RecoveryResult(
            peakHr = windowPeakHr,
            hrAt60 = hrAt60,
            hrr60 = windowPeakHr - hrAt60,
            elapsedAtEnd = elapsedSeconds,
        )
        _latest.value = result
        return result
    }

    companion object {
        const val EFFORT_THRESHOLD_WATTS = 150.0
        const val AVERAGE_WINDOW_SECONDS = 30
        const val END_FRACTION = 0.6
        const val END_CONFIRM_SECONDS = 5
        const val MIN_EFFORT_SECONDS = 30
        const val PEAK_LOOKBACK_SECONDS = 15
        const val WINDOW_SECONDS = 60
        const val MAX_HR_GAP_SECONDS = 10
    }
}
