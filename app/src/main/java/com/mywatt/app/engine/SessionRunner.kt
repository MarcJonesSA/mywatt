package com.mywatt.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * The score for one rep (or one ramp step ridden in full). Published the second the rep
 * ends and then refined while a gated recovery after it runs: [peakHr] can still rise for
 * 30 s, [hrAt60] and [hrr60] land at 60 s, a drop is added to [minuteDrops] every full
 * minute, and [recoverySeconds] is filled when the gate opens.
 */
data class RepResult(
    /** 1-based. */
    val repNumber: Int,
    val targetWatts: Int,
    val seconds: Int,
    /**
     * Average smoothed power over the rep, rounded to whole watts; for a sprint, the best
     * rolling average of raw watts over its window. Missing power counts as 0 W.
     */
    val averageWatts: Int,
    /** The gear held for most of the rep, or null if no gear was reported. */
    val gear: Int?,
    /** HR on the rep's first second with a reading; null without a strap. */
    val preHr: Int?,
    /** Highest HR from the start of the rep to 30 s after it ended; null without a strap. */
    val peakHr: Int?,
    /** The HR the gate is waiting for (see [HrGate.thresholdHr]); null until the rise is known. */
    val gateHr: Int?,
    /** HR 60 s after the rep ended, once that second arrives. */
    val hrAt60: Int?,
    /** [peakHr] minus [hrAt60]. */
    val hrr60: Int?,
    /**
     * How far HR fell in each completed minute of the recovery: the first from the peak to
     * 60 s, the second from 60 s to 120 s, and so on. A rise shows as a negative drop.
     */
    val minuteDrops: List<Int>,
    /** How long the gated recovery after this rep ran before the gate opened, once it has. */
    val recoverySeconds: Int?,
    /** True if the recovery hit [HrGate.maxSeconds] without HR reaching the gate. */
    val gateTimedOut: Boolean,
) {
    /** Average as a fraction of target, e.g. 0.97. */
    val fractionOfTarget: Double get() = averageWatts.toDouble() / targetWatts

    /** How close the average came to target. */
    val band: Band get() = Band.of(averageWatts, targetWatts)

    /** [peakHr] minus [preHr], or null until both are known. */
    val rise: Int? get() = if (preHr != null && peakHr != null) peakHr - preHr else null
}

/** Which half of a gated recovery the rider is in. */
enum class RecoveryPhase {
    /** The first 30 s after the rep, while HR is still climbing and the peak is being fixed. */
    BUILDING,

    /** Peak fixed; waiting for HR to fall to the gate. */
    RECOVERING,
}

/** Everything the screen needs about the session right now. */
data class SessionState(
    val session: Session,
    /** Index into [Session.blocks]; equal to the block count once [finished]. */
    val blockIndex: Int,
    /** The block in progress, or null once [finished]. */
    val block: Block?,
    /** The ride-log tag of the current block (warmup, rep1, rec1, ..., cooldown); null once finished. */
    val blockTag: String?,
    /** Seconds spent in the current block so far. */
    val secondsInBlock: Int,
    /**
     * Seconds until a timed block ends (in a ramp, until the step ends); null in a gated
     * recovery and once finished.
     */
    val secondsLeft: Int?,
    /** The rep in progress, or the one whose recovery is running; 0 before the first rep. */
    val repNumber: Int,
    /** The current rep's (or ramp step's) target; null outside a scored effort. */
    val targetWatts: Int?,
    /** Running average watts during a rep; null outside one. */
    val liveRepAverage: Int?,
    /** The band of this second's smoothed watts against target during a rep; null outside one. */
    val band: Band?,
    /**
     * True for the first [SessionRunner.GEAR_NAG_SECONDS] of a rep when the gear on the
     * rep's first second was below [SessionRunner.GEAR_NAG_BELOW], or unknown.
     */
    val gearNag: Boolean,
    /** Set during a gated recovery, null otherwise. */
    val recoveryPhase: RecoveryPhase?,
    /** The HR the gate is waiting for during a recovery, once the rise is known and HR is still arriving. */
    val gateHr: Int?,
    /** Results for every rep completed so far, in order. */
    val reps: List<RepResult>,
    val finished: Boolean,
    /** One line for the screen, e.g. "Rep 2/3 · 250 W · avg 243 W · 0:41 left". */
    val statusLine: String,
    /** The target of an unscored block (warm-up, build, easy, cool-down) that has one; null otherwise. */
    val easyTargetWatts: Int? = null,
    /** Cadence the current rep asks for, if any. */
    val cadenceTarget: Int? = null,
    /** The set the current rep belongs to, 0 outside a session of sets. */
    val setNumber: Int = 0,
    /** 1-based step of a ramp in progress; null outside a ramp. */
    val rampStep: Int? = null,
) {
    companion object {
        /** The state before the first second is fed. */
        fun ready(session: Session): SessionState {
            val first = session.blocks.first()
            return SessionState(
                session = session,
                blockIndex = 0,
                block = first,
                blockTag = session.blockTag(0),
                secondsInBlock = 0,
                secondsLeft = first.fixedSeconds ?: (first as? Ramp)?.stepSeconds,
                repNumber = 0,
                targetWatts = first.scoredTarget(0, 0),
                liveRepAverage = null,
                band = null,
                gearNag = false,
                recoveryPhase = null,
                gateHr = null,
                reps = emptyList(),
                finished = false,
                statusLine = "Ready · ${session.name}",
                easyTargetWatts = first.easyTarget(0),
                cadenceTarget = (first as? Rep)?.cadenceRpm,
                setNumber = (first as? Rep)?.set ?: 0,
                rampStep = if (first is Ramp) 1 else null,
            )
        }
    }
}

/** Length of a timed block, or null for a gated recovery or a ramp. */
val Block.fixedSeconds: Int?
    get() = when (this) {
        is WarmUp -> seconds
        is Rep -> seconds
        is Easy -> seconds
        is CoolDown -> seconds
        is Recovery, is Ramp -> null
    }

/** The target of a scored effort at [secondsIn] (a ramp's [rampIndex] step), or null. */
private fun Block.scoredTarget(secondsIn: Int, rampIndex: Int): Int? = when (this) {
    is Rep -> targetWatts
    is Ramp -> stepTarget(rampIndex)
    else -> null
}

/** The target of an unscored block at [secondsIn], or null. */
private fun Block.easyTarget(secondsIn: Int): Int? = when (this) {
    is WarmUp -> targetAt(secondsIn)
    is Easy -> targetWatts
    is CoolDown -> targetWatts
    else -> null
}

/**
 * Walks a [Session] block by block. Pure Kotlin; call [onSecond] once a second with the
 * ride clock and that second's readings.
 *
 * Timing: the first call starts block 0 on that second. A timed block that started at
 * second S lasts through second S + length - 1; the call for second S + length moves to
 * the next block and is counted against it, so no second is lost or counted twice. A gated
 * recovery ends on the second its [HrGate] opens and a ramp on the second it fails, and
 * that second likewise belongs to the block after it.
 *
 * Rep scoring: the average of smoothed watts over the rep's seconds, a missing reading
 * counting as 0 W as [HeartRateRecovery] does; a rep with a best window is scored on the
 * best rolling average of raw watts instead. Peak HR for a rep is the highest reading from
 * the rep's first second to [HeartRateRecovery.PEAK_LOOKAHEAD_SECONDS] after its end, so
 * the recovery that follows keeps feeding the rep's result for its first 30 s. A 60 s
 * recovery reading (HRR60) is taken against the same peak, as [HeartRateRecovery] does.
 *
 * Reps ridden back to back with no recovery between (a stepdown) count as one effort for
 * the gate: its rise runs from the HR at the start of the first of them.
 *
 * The HR gate: see [HrGate]. HR missing for more than [HeartRateRecovery.MAX_HR_GAP_SECONDS]
 * seconds in a row, or never present, makes the gate fall back to a fixed length.
 *
 * The ramp: see [Ramp]. Each step ridden in full is added to the reps; the step under way
 * when it fails is dropped.
 */
class SessionRunner(val session: Session) {

    private val _state = MutableStateFlow(SessionState.ready(session))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var started = false
    private var finished = false
    private var blockIndex = 0
    private var blockStartSeconds = 0L

    private val reps = ArrayList<RepResult>()

    // Rep (or ramp step) in progress.
    private var repWattsSum = 0L
    private var repTicks = 0
    private val repRawWatts = ArrayList<Int>()
    private val repGearCounts = HashMap<Int, Int>()
    private var repStartGear: Int? = null
    private var repPreHr: Int? = null
    private var repPeakHr: Int? = null

    // The run of back-to-back reps the next gate judges.
    private var effortPreHr: Int? = null
    private var effortPeakHr: Int? = null

    // Ramp in progress.
    private var rampStepIndex = 0
    private var rampLowCadence = 0
    private var rampLowPower = 0

    // Recovery in progress.
    private var recoveryLastHr: Int? = null
    private var recoveryMissingHr = 0
    private var recoveryHrLost = false
    private var recoveryMinuteMarkHr: Int? = null

    /**
     * Feeds one second. [smoothedWatts] null counts as 0 W, [hrBpm] null means the strap
     * gave nothing this second, [gear] null means the bike has not reported one.
     * [rawWatts] (for sprints) defaults to the smoothed figure and [cadenceRpm] (for the
     * ramp) null counts as not pedalling.
     */
    fun onSecond(
        elapsedSeconds: Long,
        smoothedWatts: Int?,
        hrBpm: Int?,
        gear: Int?,
        rawWatts: Int? = smoothedWatts,
        cadenceRpm: Int? = null,
    ) {
        if (finished) return
        if (!started) {
            started = true
            enterBlock(0, elapsedSeconds)
        }

        val watts = smoothedWatts ?: 0

        // Move past every block whose time is up, then account this second to the one
        // that is current. A recovery is judged after it has seen this second's HR, and a
        // ramp after it has seen this second's power and cadence.
        while (!finished) {
            val block = session.blocks[blockIndex]
            val secondsIn = (elapsedSeconds - blockStartSeconds).toInt()
            when (block) {
                is WarmUp, is CoolDown, is Easy -> {
                    val length = block.fixedSeconds!!
                    if (secondsIn >= length) {
                        enterBlock(blockIndex + 1, blockStartSeconds + length)
                        continue
                    }
                    break
                }

                is Rep -> {
                    if (secondsIn >= block.seconds) {
                        finishRep(block)
                        enterBlock(blockIndex + 1, blockStartSeconds + block.seconds)
                        continue
                    }
                    accumulate(watts, rawWatts, hrBpm, gear)
                    break
                }

                is Recovery -> {
                    ingestRecoverySecond(block.gate, secondsIn, hrBpm)
                    val verdict = gateVerdict(block.gate, secondsIn)
                    if (verdict != GateVerdict.CLOSED) {
                        closeRecovery(secondsIn, timedOut = verdict == GateVerdict.TIMED_OUT)
                        enterBlock(blockIndex + 1, elapsedSeconds)
                        continue
                    }
                    break
                }

                is Ramp -> {
                    val step = secondsIn / block.stepSeconds
                    if (step > rampStepIndex) {
                        finishRampStep(block)
                        rampStepIndex = step
                        resetRep()
                    }
                    accumulate(watts, rawWatts, hrBpm, gear)
                    val cadenceLow = cadenceRpm == null || cadenceRpm < block.cadenceFloorRpm
                    rampLowCadence = if (cadenceLow) rampLowCadence + 1 else 0
                    rampLowPower = if (watts < block.stepTarget(rampStepIndex)) rampLowPower + 1 else 0
                    if (rampLowCadence >= block.cadenceFailSeconds || rampLowPower >= block.powerFailSeconds) {
                        enterBlock(blockIndex + 1, elapsedSeconds)
                        continue
                    }
                    break
                }
            }
        }

        publish(elapsedSeconds, watts, hrBpm, gear)
    }

    /**
     * Makes block [index] current from [startSeconds]. A timed block hands over at its
     * scheduled end even if the ride clock skipped past it, so a gap in readings does not
     * push every later block back; a recovery or ramp hands over on the second it ended.
     */
    private fun enterBlock(index: Int, startSeconds: Long) {
        val previous = session.blocks.getOrNull(blockIndex)
        blockIndex = index
        blockStartSeconds = startSeconds
        if (index >= session.blocks.size) {
            finished = true
            return
        }
        when (session.blocks[index]) {
            is Rep -> {
                resetRep()
                // A rep straight after another rep extends the same effort.
                if (previous !is Rep || index == 0) {
                    effortPreHr = null
                    effortPeakHr = null
                }
            }
            is Ramp -> {
                resetRep()
                rampStepIndex = 0
                rampLowCadence = 0
                rampLowPower = 0
            }
            is Recovery -> {
                recoveryLastHr = null
                recoveryMissingHr = 0
                recoveryHrLost = false
                recoveryMinuteMarkHr = null
            }
            else -> Unit
        }
    }

    private fun resetRep() {
        repWattsSum = 0
        repTicks = 0
        repRawWatts.clear()
        repGearCounts.clear()
        repStartGear = null
        repPreHr = null
        repPeakHr = null
    }

    private fun accumulate(watts: Int, rawWatts: Int?, hrBpm: Int?, gear: Int?) {
        if (repTicks == 0) repStartGear = gear
        repWattsSum += watts
        repTicks++
        repRawWatts += (rawWatts ?: 0).coerceAtLeast(0)
        if (gear != null) repGearCounts[gear] = (repGearCounts[gear] ?: 0) + 1
        if (hrBpm != null) {
            if (repPreHr == null) repPreHr = hrBpm
            repPeakHr = maxOf(repPeakHr ?: hrBpm, hrBpm)
            if (effortPreHr == null) effortPreHr = hrBpm
            effortPeakHr = maxOf(effortPeakHr ?: hrBpm, hrBpm)
        }
    }

    private fun smoothedAverage(): Int =
        if (repTicks == 0) 0 else (repWattsSum.toDouble() / repTicks).roundToInt()

    private fun finishRep(rep: Rep) {
        val average = rep.bestWindowSeconds?.let { bestAverage(repRawWatts, it) } ?: smoothedAverage()
        val gate = (session.blocks.getOrNull(blockIndex + 1) as? Recovery)?.gate
        reps += RepResult(
            repNumber = reps.size + 1,
            targetWatts = rep.targetWatts,
            seconds = rep.seconds,
            averageWatts = average,
            gear = repGearCounts.maxByOrNull { it.value }?.key,
            preHr = repPreHr,
            peakHr = repPeakHr,
            gateHr = gate?.thresholdHr(effortPreHr, effortPeakHr),
            hrAt60 = null,
            hrr60 = null,
            minuteDrops = emptyList(),
            recoverySeconds = null,
            gateTimedOut = false,
        )
    }

    private fun finishRampStep(ramp: Ramp) {
        reps += RepResult(
            repNumber = reps.size + 1,
            targetWatts = ramp.stepTarget(rampStepIndex),
            seconds = ramp.stepSeconds,
            averageWatts = smoothedAverage(),
            gear = repGearCounts.maxByOrNull { it.value }?.key,
            preHr = repPreHr,
            peakHr = repPeakHr,
            gateHr = null,
            hrAt60 = null,
            hrr60 = null,
            minuteDrops = emptyList(),
            recoverySeconds = null,
            gateTimedOut = false,
        )
    }

    /** The rep this recovery follows, if the block before it was a rep. */
    private fun recoveringRep(): RepResult? =
        if (blockIndex > 0 && session.blocks[blockIndex - 1] is Rep) reps.lastOrNull() else null

    private fun ingestRecoverySecond(gate: HrGate, sinceEnd: Int, hrBpm: Int?) {
        if (hrBpm == null) {
            recoveryMissingHr++
            if (recoveryMissingHr > HeartRateRecovery.MAX_HR_GAP_SECONDS) recoveryHrLost = true
        } else {
            recoveryMissingHr = 0
            recoveryLastHr = hrBpm
        }

        val rep = recoveringRep() ?: return
        var updated = rep
        if (hrBpm != null && sinceEnd <= HeartRateRecovery.PEAK_LOOKAHEAD_SECONDS) {
            val peak = maxOf(rep.peakHr ?: hrBpm, hrBpm)
            effortPeakHr = maxOf(effortPeakHr ?: hrBpm, hrBpm)
            if (peak != rep.peakHr) updated = updated.copy(peakHr = peak)
            val threshold = gate.thresholdHr(effortPreHr, effortPeakHr)
            if (threshold != updated.gateHr) updated = updated.copy(gateHr = threshold)
        }
        val hrNow = recoveryLastHr
        val onMinute = sinceEnd > 0 && sinceEnd % HeartRateRecovery.SHORT_WINDOW_SECONDS == 0
        if (onMinute && hrNow != null && !recoveryHrLost) {
            val from = recoveryMinuteMarkHr ?: updated.peakHr
            if (from != null) updated = updated.copy(minuteDrops = updated.minuteDrops + (from - hrNow))
            recoveryMinuteMarkHr = hrNow
            val peak = updated.peakHr
            if (sinceEnd == HeartRateRecovery.SHORT_WINDOW_SECONDS && peak != null) {
                updated = updated.copy(hrAt60 = hrNow, hrr60 = peak - hrNow)
            }
        }
        if (updated != rep) reps[reps.lastIndex] = updated
    }

    private enum class GateVerdict { CLOSED, OPEN, TIMED_OUT }

    private fun gateVerdict(gate: HrGate, sinceEnd: Int): GateVerdict {
        val threshold = recoveringRep()?.gateHr
        val hrNow = recoveryLastHr
        if (recoveryHrLost || threshold == null || hrNow == null) {
            return if (sinceEnd >= gate.fallbackSeconds) GateVerdict.OPEN else GateVerdict.CLOSED
        }
        if (sinceEnd >= gate.minSeconds && hrNow <= threshold) return GateVerdict.OPEN
        if (sinceEnd >= gate.maxSeconds) return GateVerdict.TIMED_OUT
        return GateVerdict.CLOSED
    }

    private fun closeRecovery(sinceEnd: Int, timedOut: Boolean) {
        val rep = recoveringRep() ?: return
        reps[reps.lastIndex] = rep.copy(recoverySeconds = sinceEnd, gateTimedOut = timedOut)
    }

    private fun publish(elapsedSeconds: Long, watts: Int, hrBpm: Int?, gear: Int?) {
        val snapshot = reps.toList()
        if (finished) {
            _state.value = SessionState(
                session = session,
                blockIndex = session.blocks.size,
                block = null,
                blockTag = null,
                secondsInBlock = 0,
                secondsLeft = null,
                repNumber = snapshot.size,
                targetWatts = null,
                liveRepAverage = null,
                band = null,
                gearNag = false,
                recoveryPhase = null,
                gateHr = null,
                reps = snapshot,
                finished = true,
                statusLine = finishedLine(snapshot),
            )
            return
        }

        val block = session.blocks[blockIndex]
        val secondsIn = (elapsedSeconds - blockStartSeconds).toInt()
        val scored = block is Rep || block is Ramp
        val repNumber = snapshot.size + if (scored) 1 else 0
        val liveAverage = if (scored && repTicks > 0) smoothedAverage() else null
        val secondsLeft = when (block) {
            is Ramp -> block.stepSeconds - secondsIn % block.stepSeconds
            else -> block.fixedSeconds?.let { it - secondsIn }
        }
        val target = block.scoredTarget(secondsIn, rampStepIndex)
        val band = target?.let { Band.of(watts, it) }
        val gearNag = block is Rep && block.kind.gearNag &&
            secondsIn < GEAR_NAG_SECONDS && gearNagDue(repStartGear)
        val phase = when {
            block !is Recovery -> null
            secondsIn <= HeartRateRecovery.PEAK_LOOKAHEAD_SECONDS -> RecoveryPhase.BUILDING
            else -> RecoveryPhase.RECOVERING
        }
        val gateHr = if (block is Recovery && !recoveryHrLost) recoveringRep()?.gateHr else null
        val easyTarget = block.easyTarget(secondsIn)

        _state.value = SessionState(
            session = session,
            blockIndex = blockIndex,
            block = block,
            blockTag = session.blockTag(blockIndex),
            secondsInBlock = secondsIn,
            secondsLeft = secondsLeft,
            repNumber = repNumber,
            targetWatts = target,
            liveRepAverage = liveAverage,
            band = band,
            gearNag = gearNag,
            recoveryPhase = phase,
            gateHr = gateHr,
            reps = snapshot,
            finished = false,
            statusLine = statusLine(block, secondsIn, secondsLeft, repNumber, liveAverage, phase, gateHr, hrBpm, easyTarget),
            easyTargetWatts = easyTarget,
            cadenceTarget = (block as? Rep)?.cadenceRpm,
            setNumber = (block as? Rep)?.set ?: 0,
            rampStep = if (block is Ramp) rampStepIndex + 1 else null,
        )
    }

    /** The nag fires when the rep started in a gear below [GEAR_NAG_BELOW], or in no known gear. */
    private fun gearNagDue(startGear: Int?): Boolean = startGear == null || startGear < GEAR_NAG_BELOW

    private fun statusLine(
        block: Block,
        secondsIn: Int,
        secondsLeft: Int?,
        repNumber: Int,
        liveAverage: Int?,
        phase: RecoveryPhase?,
        gateHr: Int?,
        hrBpm: Int?,
        easyTarget: Int?,
    ): String = when (block) {
        is WarmUp, is CoolDown, is Easy -> buildString {
            append(block.label)
            if (easyTarget != null) append(" · $easyTarget W")
            append(" · ${clock(secondsLeft!!)} left")
        }

        is Rep -> buildString {
            append("${block.label} $repNumber/${session.repCount} · ${block.targetWatts} W")
            if (liveAverage != null) append(" · avg $liveAverage W")
            append(" · ${clock(secondsLeft!!)} left")
        }

        is Ramp -> buildString {
            append("Ramp · step ${rampStepIndex + 1} · ${block.stepTarget(rampStepIndex)} W")
            if (liveAverage != null) append(" · avg $liveAverage W")
            append(" · ${clock(secondsLeft!!)} left")
        }

        is Recovery -> buildString {
            append("Recovery $repNumber/${session.repCount} · ")
            append(if (phase == RecoveryPhase.BUILDING) "Building" else "Recovering")
            when {
                recoveryHrLost -> append(" · no HR")
                hrBpm != null -> append(" · HR $hrBpm")
            }
            if (gateHr != null) append(" · gate $gateHr")
            append(" · ${clock(secondsIn)}")
        }
    }

    private fun finishedLine(reps: List<RepResult>): String {
        if (reps.isEmpty()) return "Session complete"
        return "Session complete · " + reps.joinToString(" / ") { it.averageWatts.toString() } + " W"
    }

    private fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        /** How long "Gear up" shows at the start of a rep when the nag is due. */
        const val GEAR_NAG_SECONDS = 5

        /** A rep started in a gear below this gets the nag. */
        const val GEAR_NAG_BELOW = 10

        /**
         * The best rolling average of [window] consecutive values, rounded; the plain
         * average if there are fewer than [window] values, 0 if there are none.
         */
        fun bestAverage(values: List<Int>, window: Int): Int {
            if (values.isEmpty()) return 0
            if (values.size <= window) return values.average().roundToInt()
            var sum = values.subList(0, window).sumOf { it.toLong() }
            var best = sum
            for (i in window until values.size) {
                sum += values[i] - values[i - window]
                if (sum > best) best = sum
            }
            return (best.toDouble() / window).roundToInt()
        }
    }
}
