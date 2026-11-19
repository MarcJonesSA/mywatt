package com.mywatt.app.engine

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One block of a training session. A session is just an ordered list of these; the
 * [SessionRunner] walks them once a second.
 */
sealed interface Block {
    /** Short name for the status line and the ride log. */
    val label: String
}

/**
 * Easy riding for a fixed time before the first rep, at [targetWatts] if one is given. A
 * different [endTargetWatts] makes it a build: the target rises in a straight line from
 * the first second to the last.
 */
data class WarmUp(
    val seconds: Int,
    val targetWatts: Int? = null,
    val endTargetWatts: Int? = targetWatts,
) : Block {
    override val label: String
        get() = if (endTargetWatts != targetWatts) "Build" else "Warm-up"

    /** The target on second [secondsIn] of the block, or null if the block has none. */
    fun targetAt(secondsIn: Int): Int? {
        val from = targetWatts ?: return null
        val to = endTargetWatts ?: from
        if (seconds <= 1) return from
        val t = secondsIn.coerceIn(0, seconds - 1).toDouble() / (seconds - 1)
        return (from + (to - from) * t).roundToInt()
    }
}

/** What a [Rep] is, which decides its label and whether the gear nag applies. */
enum class RepKind(val label: String, val gearNag: Boolean) {
    /** A hard interval. */
    INTERVAL("Rep", true),

    /** A long block held at one effort, e.g. a Z2 ride; no low-gear nag. */
    STEADY("Steady", false),

    /** A test effort ridden by feel; the target shown is the rider's current FTP. */
    SELF_PACED("Test effort", true),

    /** A short all-out effort scored on raw watts. */
    SPRINT("Sprint", true),
}

/**
 * A fixed-length effort. Scored as the average smoothed power held over it, unless
 * [bestWindowSeconds] is set, in which case it is the best rolling average of raw watts
 * over that many seconds within the rep (e.g. the best 10 s of a 14 s sprint window).
 * [cadenceRpm] is a cadence to hold, shown to the rider but not scored. [set] numbers the
 * set this rep belongs to in a session of sets, 0 otherwise.
 */
data class Rep(
    val seconds: Int,
    val targetWatts: Int,
    val cadenceRpm: Int? = null,
    val bestWindowSeconds: Int? = null,
    val kind: RepKind = RepKind.INTERVAL,
    val set: Int = 0,
) : Block {
    override val label: String get() = kind.label
}

/**
 * Easy riding whose length is decided by the heart-rate gate rather than the clock: the
 * next block starts once HR has come down far enough from the rep's peak.
 */
data class Recovery(val gate: HrGate = HrGate()) : Block {
    override val label: String get() = "Recovery"
}

/** A recovery of fixed length, not gated, at [targetWatts] if one is given. */
data class Easy(val seconds: Int, val targetWatts: Int? = null) : Block {
    override val label: String get() = "Easy"
}

/** Easy riding for a fixed time after the last rep, at [targetWatts] if one is given. */
data class CoolDown(val seconds: Int, val targetWatts: Int? = null) : Block {
    override val label: String get() = "Cool-down"
}

/**
 * A ramp test that ends itself. Steps of [stepSeconds] start at [startWatts] and rise by
 * [stepWatts]; each step ridden in full is scored like a rep. The ramp ends on the second
 * cadence has been under [cadenceFloorRpm] (or missing) for [cadenceFailSeconds] in a row,
 * or smoothed power under the step's target for [powerFailSeconds] in a row.
 */
data class Ramp(
    val startWatts: Int,
    val stepWatts: Int,
    val stepSeconds: Int = 60,
    val cadenceFloorRpm: Int = 60,
    val cadenceFailSeconds: Int = 5,
    val powerFailSeconds: Int = 10,
) : Block {
    override val label: String get() = "Ramp"

    /** Target of step [index], 0-based. */
    fun stepTarget(index: Int): Int = startWatts + stepWatts * index
}

/** An FTP test, and how its result turns into an FTP estimate. */
enum class FtpTest(val factor: Double) {
    /** 75% of the last ramp step ridden in full. */
    RAMP(0.75),

    /** 95% of the average held over the 20-minute effort. */
    TWENTY_MINUTE(0.95);

    /**
     * The FTP estimate from a test's completed reps, or null if the deciding effort was
     * not completed: for the ramp the last full step's target, for the 20-minute test the
     * self-paced effort's average.
     */
    fun estimate(reps: List<RepResult>): Int? {
        val basis = when (this) {
            RAMP -> reps.lastOrNull()?.targetWatts
            TWENTY_MINUTE -> reps.lastOrNull()?.averageWatts
        } ?: return null
        return (basis * factor).roundToInt()
    }
}

/** How the session screen leads: with the rev counter, or with the big watts number. */
enum class ScreenMode { Dial, NumberFirst }

/**
 * The rule that ends a gated [Recovery], set by the rider's [RecoverySetting].
 *
 * The effort's HR rise is its peak HR minus the HR on the first second of the effort (for
 * reps ridden back to back, the first of them). The gate opens on the first second, at
 * least [minSeconds] after the rep ended, on which HR is at or below peak minus
 * [recoveredFraction] of that rise. HR keeps climbing for up to 30 s after the legs stop,
 * so no setting lets the gate open before the peak is fixed.
 *
 * If HR never gets there the gate opens at [maxSeconds] and the rep is marked as having
 * timed out. If there is no strap, or the strap goes quiet for more than
 * [HeartRateRecovery.MAX_HR_GAP_SECONDS], the gate cannot judge recovery and instead opens
 * at [fallbackSeconds].
 */
data class HrGate(
    val setting: RecoverySetting = RecoverySetting.DEFAULT,
    val fallbackSeconds: Int = DEFAULT_FALLBACK_SECONDS,
    /** The setting's timeout unless overridden. */
    val maxSeconds: Int = setting.timeoutSeconds,
) {
    /** Share of the rise that must be recovered, from [setting]. */
    val recoveredFraction: Double get() = setting.releaseFraction

    /** Shortest recovery, from [setting]. */
    val minSeconds: Int get() = setting.minRecoverySeconds

    init {
        require(maxSeconds >= setting.minRecoverySeconds) { "maxSeconds must be at least minSeconds" }
        require(fallbackSeconds > 0) { "fallbackSeconds must be positive" }
    }

    /**
     * The HR the gate is waiting for, given the effort's [preHr] and [peakHr]: the highest
     * whole bpm that counts as recovered. Null until both are known.
     */
    fun thresholdHr(preHr: Int?, peakHr: Int?): Int? {
        if (preHr == null || peakHr == null) return null
        val rise = (peakHr - preHr).coerceAtLeast(0)
        // A hair of tolerance so 0.6 * 50 = 30.000000000000004 does not round a bpm away.
        return floor(peakHr - rise * recoveredFraction + 1e-9).toInt()
    }

    companion object {
        const val DEFAULT_FALLBACK_SECONDS = 120
    }
}

/**
 * A named, ordered list of blocks, with the library [id] it was built from (empty for one
 * built by hand), the [recoverySetting] its gated recoveries use, the [ftpWatts] its
 * targets were worked out from, the [screenMode] to show it in and, for a test, which
 * [ftpTest] it is.
 */
data class Session(
    val name: String,
    val blocks: List<Block>,
    val recoverySetting: RecoverySetting = RecoverySetting.DEFAULT,
    val id: String = "",
    val ftpWatts: Int? = null,
    val screenMode: ScreenMode = ScreenMode.NumberFirst,
    val ftpTest: FtpTest? = null,
) {

    init {
        require(blocks.isNotEmpty()) { "a session needs at least one block" }
        blocks.forEach { block ->
            when (block) {
                is WarmUp -> require(block.seconds > 0) { "warm-up must be longer than 0 s" }
                is Rep -> {
                    require(block.seconds > 0) { "rep must be longer than 0 s" }
                    require(block.targetWatts > 0) { "rep target must be positive" }
                    block.bestWindowSeconds?.let {
                        require(it in 1..block.seconds) { "best window must fit inside the rep" }
                    }
                }
                is Easy -> require(block.seconds > 0) { "easy block must be longer than 0 s" }
                is CoolDown -> require(block.seconds > 0) { "cool-down must be longer than 0 s" }
                is Ramp -> {
                    require(block.startWatts > 0 && block.stepWatts > 0) { "ramp steps must be positive" }
                    require(block.stepSeconds > 0) { "ramp step must be longer than 0 s" }
                }
                is Recovery -> Unit
            }
        }
    }

    /** How many reps the session contains. A ramp's steps are not known in advance and not counted. */
    val repCount: Int get() = blocks.count { it is Rep }

    /** How many sets the session is split into, 0 if it is not. */
    val setCount: Int get() = blocks.filterIsInstance<Rep>().maxOfOrNull { it.set } ?: 0

    /**
     * The tag the ride log uses for block [index]: warmup, rep1, rec1, rep2, rec2, ...,
     * cooldown. A recovery, gated or fixed, takes the number of the rep before it; a ramp
     * is tagged ramp.
     */
    fun blockTag(index: Int): String {
        val repsSoFar = blocks.subList(0, (index + 1).coerceAtMost(blocks.size)).count { it is Rep }
        return when (blocks[index]) {
            is WarmUp -> "warmup"
            is Rep -> "rep$repsSoFar"
            is Recovery, is Easy -> "rec$repsSoFar"
            is CoolDown -> "cooldown"
            is Ramp -> "ramp"
        }
    }

    companion object {
        const val THREE_BY_ONE_NAME = "Three by One"
        const val DEFAULT_TARGET_WATTS = 250

        /**
         * Three by One at a fixed wattage: 5 min warm-up, three times [1 min at
         * [targetWatts], gated recovery], then 3 min cool-down. The app builds its
         * sessions from the [Library]; this fixed-watts form is kept for tests.
         */
        fun threeByOne(
            targetWatts: Int = DEFAULT_TARGET_WATTS,
            setting: RecoverySetting = RecoverySetting.DEFAULT,
        ): Session {
            val blocks = ArrayList<Block>()
            blocks += WarmUp(seconds = 300)
            repeat(3) {
                blocks += Rep(seconds = 60, targetWatts = targetWatts)
                blocks += Recovery(HrGate(setting))
            }
            blocks += CoolDown(seconds = 180)
            return Session(THREE_BY_ONE_NAME, blocks, setting)
        }
    }
}
