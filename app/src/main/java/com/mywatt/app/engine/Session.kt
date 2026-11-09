package com.mywatt.app.engine

/**
 * One block of a training session. A session is just an ordered list of these; the
 * [SessionRunner] walks them once a second.
 */
sealed interface Block {
    /** Short name for the status line and the ride log. */
    val label: String
}

/** Easy riding for a fixed time before the first rep. */
data class WarmUp(val seconds: Int) : Block {
    override val label: String get() = "Warm-up"
}

/** A fixed-length effort scored as the average smoothed power held over it. */
data class Rep(val seconds: Int, val targetWatts: Int) : Block {
    override val label: String get() = "Rep"
}

/**
 * Easy riding whose length is decided by the heart-rate gate rather than the clock: the
 * next block starts once HR has come down far enough from the rep's peak.
 */
data class Recovery(val gate: HrGate = HrGate()) : Block {
    override val label: String get() = "Recovery"
}

/** Easy riding for a fixed time after the last rep. */
data class CoolDown(val seconds: Int) : Block {
    override val label: String get() = "Cool-down"
}

/**
 * The rule that ends a gated [Recovery].
 *
 * The rep's HR rise is its peak HR minus the HR on its first second. The gate opens on the
 * first second, at least [minSeconds] after the rep ended, on which HR is at or below
 * peak minus [recoveredFraction] of that rise, i.e. half the rise has been recovered by
 * default. [minSeconds] defaults to 60 s so the peak has time to form (HR keeps climbing
 * for up to 30 s after the legs stop) and so every rep gets a 60 s recovery reading.
 *
 * If HR never gets there the gate opens at [maxSeconds] and the rep is marked as having
 * timed out. If there is no strap, or the strap goes quiet for more than
 * [HeartRateRecovery.MAX_HR_GAP_SECONDS], the gate cannot judge recovery and instead opens
 * at [fallbackSeconds].
 */
data class HrGate(
    val recoveredFraction: Double = DEFAULT_RECOVERED_FRACTION,
    val minSeconds: Int = DEFAULT_MIN_SECONDS,
    val maxSeconds: Int = DEFAULT_MAX_SECONDS,
    val fallbackSeconds: Int = DEFAULT_FALLBACK_SECONDS,
) {
    init {
        require(recoveredFraction > 0.0 && recoveredFraction <= 1.0) { "recoveredFraction must be in (0, 1]" }
        require(minSeconds >= 0) { "minSeconds must not be negative" }
        require(maxSeconds >= minSeconds) { "maxSeconds must be at least minSeconds" }
        require(fallbackSeconds > 0) { "fallbackSeconds must be positive" }
    }

    /**
     * The HR the gate is waiting for, given the rep's [preHr] and [peakHr]: the highest
     * whole bpm that counts as recovered. Null until both are known.
     */
    fun thresholdHr(preHr: Int?, peakHr: Int?): Int? {
        if (preHr == null || peakHr == null) return null
        val rise = (peakHr - preHr).coerceAtLeast(0)
        return kotlin.math.floor(peakHr - rise * recoveredFraction).toInt()
    }

    companion object {
        const val DEFAULT_RECOVERED_FRACTION = 0.5
        const val DEFAULT_MIN_SECONDS = 60
        const val DEFAULT_MAX_SECONDS = 240
        const val DEFAULT_FALLBACK_SECONDS = 120
    }
}

/** A named, ordered list of blocks. */
data class Session(val name: String, val blocks: List<Block>) {

    init {
        require(blocks.isNotEmpty()) { "a session needs at least one block" }
        blocks.forEach { block ->
            when (block) {
                is WarmUp -> require(block.seconds > 0) { "warm-up must be longer than 0 s" }
                is Rep -> {
                    require(block.seconds > 0) { "rep must be longer than 0 s" }
                    require(block.targetWatts > 0) { "rep target must be positive" }
                }
                is CoolDown -> require(block.seconds > 0) { "cool-down must be longer than 0 s" }
                is Recovery -> Unit
            }
        }
    }

    /** How many reps the session contains. */
    val repCount: Int get() = blocks.count { it is Rep }

    /**
     * The tag the ride log uses for block [index]: warmup, rep1, rec1, rep2, rec2, ...,
     * cooldown. A recovery takes the number of the rep before it.
     */
    fun blockTag(index: Int): String {
        val repsSoFar = blocks.subList(0, (index + 1).coerceAtMost(blocks.size)).count { it is Rep }
        return when (blocks[index]) {
            is WarmUp -> "warmup"
            is Rep -> "rep$repsSoFar"
            is Recovery -> "rec$repsSoFar"
            is CoolDown -> "cooldown"
        }
    }

    companion object {
        const val THREE_BY_ONE_NAME = "Three by One"
        const val DEFAULT_TARGET_WATTS = 250

        /**
         * The built-in session: 5 min warm-up, three times [1 min at [targetWatts], gated
         * recovery], then 3 min cool-down.
         */
        fun threeByOne(targetWatts: Int = DEFAULT_TARGET_WATTS): Session {
            val blocks = ArrayList<Block>()
            blocks += WarmUp(seconds = 300)
            repeat(3) {
                blocks += Rep(seconds = 60, targetWatts = targetWatts)
                blocks += Recovery()
            }
            blocks += CoolDown(seconds = 180)
            return Session(THREE_BY_ONE_NAME, blocks)
        }
    }
}
