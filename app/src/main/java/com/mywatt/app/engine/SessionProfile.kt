package com.mywatt.app.engine

import kotlin.math.floor

/**
 * One bar of a session's profile: [seconds] wide from [startSeconds], rising from
 * [startPercent] to [endPercent] of FTP (equal for a flat block; a build slopes). A
 * [hatched] bar is a gated recovery, whose real length is unknown and is drawn at a
 * nominal width. Several bars can share a [blockIndex]: a ramp is drawn as its steps.
 */
data class ProfileBar(
    val blockIndex: Int,
    val startSeconds: Int,
    val seconds: Int,
    val startPercent: Double,
    val endPercent: Double = startPercent,
    val hatched: Boolean = false,
    /** True for a scored effort (rep, ramp step), false for easy riding. */
    val effort: Boolean = false,
) {
    val endSeconds: Int get() = startSeconds + seconds
}

/**
 * The shape of a [Session] for the profile graph: one bar per block, width = time,
 * height = target as % of FTP. Pure Kotlin; the view only draws what this returns.
 */
object SessionProfile {

    /** A gated recovery is drawn this wide, since its real length is decided by HR. */
    const val GATED_NOMINAL_SECONDS = 120

    /** A gated recovery has no target; it is drawn this low. */
    const val GATED_PERCENT = 40.0

    /** An easy block without a target is drawn this low. */
    const val EASY_PERCENT = 50.0

    /** The FTP the bars are scaled against: the session's own, else a guess from its hardest rep. */
    fun ftpFor(session: Session): Int =
        session.ftpWatts
            ?: session.blocks.filterIsInstance<Rep>().maxOfOrNull { it.targetWatts }?.let { (it / 1.1).toInt() }
            ?: 200

    fun bars(session: Session): List<ProfileBar> {
        val ftp = ftpFor(session).coerceAtLeast(1)
        fun pct(watts: Int?): Double = watts?.let { it * 100.0 / ftp } ?: EASY_PERCENT
        val bars = ArrayList<ProfileBar>()
        var t = 0
        session.blocks.forEachIndexed { index, block ->
            when (block) {
                is WarmUp -> {
                    bars += ProfileBar(index, t, block.seconds, pct(block.targetWatts), pct(block.endTargetWatts))
                    t += block.seconds
                }
                is Easy -> {
                    bars += ProfileBar(index, t, block.seconds, pct(block.targetWatts))
                    t += block.seconds
                }
                is CoolDown -> {
                    bars += ProfileBar(index, t, block.seconds, pct(block.targetWatts))
                    t += block.seconds
                }
                is Rep -> {
                    bars += ProfileBar(index, t, block.seconds, pct(block.targetWatts), effort = true)
                    t += block.seconds
                }
                is Recovery -> {
                    bars += ProfileBar(index, t, GATED_NOMINAL_SECONDS, GATED_PERCENT, hatched = true)
                    t += GATED_NOMINAL_SECONDS
                }
                is Ramp -> {
                    repeat(rampNominalSteps(block, ftp)) { step ->
                        bars += ProfileBar(index, t, block.stepSeconds, pct(block.stepTarget(step)), effort = true)
                        t += block.stepSeconds
                    }
                }
            }
        }
        return bars
    }

    /** Steps a ramp is drawn with: up to a maximal power of FTP / 0.75, at least one. */
    fun rampNominalSteps(ramp: Ramp, ftp: Int): Int {
        val maximal = ftp / FtpTest.RAMP.factor
        return (floor((maximal - ramp.startWatts) / ramp.stepWatts).toInt() + 2).coerceAtLeast(1)
    }

    fun totalSeconds(bars: List<ProfileBar>): Int = bars.lastOrNull()?.endSeconds ?: 0

    /**
     * Where the rider is on the profile, in profile seconds: the start of the current
     * block's bars plus the time spent in it, held at the end of the block's drawn width
     * (a gated recovery or ramp can run past it). The end of the profile once finished.
     */
    fun positionSeconds(bars: List<ProfileBar>, blockIndex: Int, secondsInBlock: Int): Int {
        val blockBars = bars.filter { it.blockIndex == blockIndex }
        if (blockBars.isEmpty()) return totalSeconds(bars)
        val start = blockBars.first().startSeconds
        val end = blockBars.last().endSeconds
        return (start + secondsInBlock.coerceAtLeast(0)).coerceAtMost(end)
    }
}
