package com.mywatt.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Heart-rate recovery after one effort. [hrr60] is how far HR fell from the peak by 60 s
 * after the effort ended; [hrr120] the same at 120 s, null until that second arrives (and
 * staying null if the 120 s window is abandoned after the 60 s reading was taken).
 */
data class RecoveryResult(
    val peakHr: Int,
    val hrAt60: Int,
    val hrr60: Int,
    val hrAt120: Int?,
    val hrr120: Int?,
    /** Ride clock, in seconds, when the effort ended; both windows count from here. */
    val elapsedAtEnd: Long,
)

/** Where an open recovery window stands, refreshed every second for the live readout. */
data class RecoveryProgress(
    /** Seconds since the effort ended, 0..120. */
    val secondsSinceEnd: Int,
    /** This second's HR, or null if the strap gave nothing. */
    val hrBpm: Int?,
)

/**
 * 60 s and 120 s heart-rate recovery (HRR60 / HRR120) after an effort. Pure Kotlin; drive
 * it with [onSecond] once a second.
 *
 * Effort detection: the rider is in an effort while the 30 s rolling average of smoothed
 * power is at least [EFFORT_THRESHOLD_WATTS]. The effort ends when smoothed power falls
 * below 60% of that average (frozen at the moment of the drop) and stays there for
 * [END_CONFIRM_SECONDS]. Only efforts that lasted at least [MIN_EFFORT_SECONDS] from
 * detection to end count; because detection trails the real start by roughly ten seconds,
 * that is about 35 s of real work.
 *
 * Peak: HR keeps climbing for a while after the legs stop, so the peak is the highest HR
 * from [PEAK_LOOKBACK_SECONDS] before the effort ended to [PEAK_LOOKAHEAD_SECONDS] after.
 * Both windows still start at effort end. At 60 s a result with hrr60 is published; at
 * 120 s it is replaced by one that also carries hrr120. The window is abandoned if more
 * than [MAX_HR_GAP_SECONDS] of HR readings are missing, or if a new effort starts first;
 * a 60 s result already published survives the abandonment of its 120 s half. The power
 * buffer is cleared when an effort ends so that a new effort during recovery is detected
 * against the easy riding, not against the effort just finished.
 *
 * TODO: EFFORT_THRESHOLD_WATTS is fixed at 150 W; it should come from FTP later.
 */
class HeartRateRecovery {

    private enum class Phase { IDLE, IN_EFFORT, ENDING, RECOVERING }

    private class HrSample(val atSeconds: Long, val bpm: Int)

    private val _latest = MutableStateFlow<RecoveryResult?>(null)

    /** The latest result that reached at least 60 s, or null until one does. Kept until replaced. */
    val latest: StateFlow<RecoveryResult?> = _latest.asStateFlow()

    private val _progress = MutableStateFlow<RecoveryProgress?>(null)

    /** The window in progress, or null when none is open. */
    val progress: StateFlow<RecoveryProgress?> = _progress.asStateFlow()

    private var phase = Phase.IDLE
    private val powerWindow = ArrayDeque<Int>()
    private val hrHistory = ArrayDeque<HrSample>()

    private var effortStartSeconds = 0L
    private var endReferenceAverage = 0.0
    private var belowCount = 0

    private var windowStartSeconds = 0L
    private var windowPeakHr: Int? = null
    private var windowMissingHr = 0
    private var windowLastHr: Int? = null
    private var windowResult60: RecoveryResult? = null

    /**
     * Feeds one second of ride data. Returns a result on the second a reading completes:
     * at 60 s one with [RecoveryResult.hrr120] null, at 120 s one with it filled. Returns
     * null on every other second. [smoothedWatts] null counts as 0 W.
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
                    if (belowCount >= END_CONFIRM_SECONDS) endEffort(elapsedSeconds, hrBpm) else null
                } else {
                    phase = if (inEffortNow) Phase.IN_EFFORT else Phase.IDLE
                    null
                }
            }

            Phase.RECOVERING -> recoverSecond(elapsedSeconds, hrBpm, inEffortNow)
        }
    }

    /** Forgets the ride in progress but keeps [latest], for a fresh connection. */
    fun reset() {
        phase = Phase.IDLE
        powerWindow.clear()
        hrHistory.clear()
        belowCount = 0
        clearWindow()
    }

    private fun beginEffort(elapsedSeconds: Long) {
        phase = Phase.IN_EFFORT
        effortStartSeconds = elapsedSeconds
        clearWindow()
    }

    private fun endEffort(elapsedSeconds: Long, hrBpm: Int?): RecoveryResult? {
        powerWindow.clear()
        if (elapsedSeconds - effortStartSeconds < MIN_EFFORT_SECONDS) {
            phase = Phase.IDLE
            return null
        }
        phase = Phase.RECOVERING
        windowStartSeconds = elapsedSeconds
        windowPeakHr = hrHistory.maxOfOrNull { it.bpm }
        windowMissingHr = 0
        windowLastHr = null
        windowResult60 = null
        _progress.value = RecoveryProgress(0, hrBpm)
        return null
    }

    private fun recoverSecond(elapsedSeconds: Long, hrBpm: Int?, inEffortNow: Boolean): RecoveryResult? {
        val sinceEnd = (elapsedSeconds - windowStartSeconds).toInt()
        if (hrBpm == null) {
            windowMissingHr++
        } else {
            windowLastHr = hrBpm
            if (sinceEnd <= PEAK_LOOKAHEAD_SECONDS) {
                windowPeakHr = maxOf(windowPeakHr ?: hrBpm, hrBpm)
            }
        }
        _progress.value = RecoveryProgress(sinceEnd, hrBpm)

        if (windowMissingHr > MAX_HR_GAP_SECONDS) {
            abandon()
            return null
        }
        if (inEffortNow) {
            beginEffort(elapsedSeconds)
            return null
        }

        val peak = windowPeakHr
        val hrNow = windowLastHr
        return when {
            sinceEnd == SHORT_WINDOW_SECONDS -> {
                if (peak == null || hrNow == null) {
                    abandon()
                    return null
                }
                val result = RecoveryResult(
                    peakHr = peak,
                    hrAt60 = hrNow,
                    hrr60 = peak - hrNow,
                    hrAt120 = null,
                    hrr120 = null,
                    elapsedAtEnd = windowStartSeconds,
                )
                windowResult60 = result
                _latest.value = result
                result
            }

            sinceEnd >= LONG_WINDOW_SECONDS -> {
                val first = windowResult60
                if (first == null || hrNow == null) {
                    abandon()
                    return null
                }
                val result = first.copy(hrAt120 = hrNow, hrr120 = first.peakHr - hrNow)
                _latest.value = result
                clearWindow()
                phase = Phase.IDLE
                result
            }

            else -> null
        }
    }

    private fun abandon() {
        phase = Phase.IDLE
        clearWindow()
    }

    private fun clearWindow() {
        windowPeakHr = null
        windowMissingHr = 0
        windowLastHr = null
        windowResult60 = null
        _progress.value = null
    }

    companion object {
        const val EFFORT_THRESHOLD_WATTS = 150.0
        const val AVERAGE_WINDOW_SECONDS = 30
        const val END_FRACTION = 0.6
        const val END_CONFIRM_SECONDS = 5
        const val MIN_EFFORT_SECONDS = 30
        const val PEAK_LOOKBACK_SECONDS = 15
        const val PEAK_LOOKAHEAD_SECONDS = 30
        const val SHORT_WINDOW_SECONDS = 60
        const val LONG_WINDOW_SECONDS = 120
        const val MAX_HR_GAP_SECONDS = 10
    }
}
