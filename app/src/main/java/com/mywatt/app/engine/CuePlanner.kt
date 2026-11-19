package com.mywatt.app.engine

/** What kind of cue it is, which decides the beep it becomes in beep-only mode. */
enum class CueKind { COUNT, GO, INFO }

/** One spoken cue. [text] is what the voice says; in beep-only mode [kind] picks the beep. */
data class Cue(val text: String, val kind: CueKind = CueKind.INFO)

/** The three tiers of cues, by rep length. */
enum class CueTier {
    /** Reps of 30 s or less: count-in, "go", count down at the end. */
    SHORT,

    /** Reps over 30 s up to 5 min: "halfway", "30 to go", count from 10. */
    MID,

    /** Reps over 5 min and steady blocks: elapsed every 5 min, "5 to go", "1 to go". */
    LONG,
}

/** Which cues are on. The gate's recovery cues follow [enabled] alone. */
data class CueSettings(
    val enabled: Boolean = true,
    val shortTier: Boolean = true,
    val midTier: Boolean = true,
    val longTier: Boolean = true,
    /** Beeps instead of a voice, for a phone without an English voice or a rider who prefers it. */
    val beepOnly: Boolean = false,
) {
    fun allows(tier: CueTier): Boolean = enabled && when (tier) {
        CueTier.SHORT -> shortTier
        CueTier.MID -> midTier
        CueTier.LONG -> longTier
    }
}

/**
 * Decides what to say each second of a session, from the [SessionState] the runner
 * publishes. Pure Kotlin: the speaker plays what this returns. Feed it every state; a
 * state for the same second as the last one (a paused session) says nothing.
 *
 * Push words ("push", "keep going") are only ever used in the last 5 s of a rep.
 */
class CuePlanner(private val settings: CueSettings = CueSettings()) {

    private var lastKey: Pair<Int, Int>? = null
    private var previous: SessionState? = null
    private var saidComingDownFor: Int? = null

    fun cues(state: SessionState): List<Cue> {
        val key = state.blockIndex to state.secondsInBlock
        if (key == lastKey) return emptyList()
        lastKey = key
        val before = previous
        previous = state
        if (!settings.enabled || state.finished) return emptyList()
        val block = state.block ?: return emptyList()
        val out = ArrayList<Cue>()
        val entered = before == null || before.blockIndex != state.blockIndex

        // ---- Starting a rep --------------------------------------------------------------
        if (entered && block is Rep) {
            val fromTimedOutGate = before?.block is Recovery && state.reps.lastOrNull()?.gateTimedOut == true
            when {
                fromTimedOutGate -> out += Cue("That's the max rest, go", CueKind.GO)
                settings.allows(tierOf(block)) && tierOf(block) == CueTier.SHORT -> out += Cue("Go", CueKind.GO)
            }
        }

        // ---- Inside the block -------------------------------------------------------------
        when (block) {
            is Rep -> out += repCues(state, block)
            is WarmUp, is Easy, is CoolDown -> out += steadyCues(state, block.fixedSeconds!!)
            is Recovery -> out += gateCues(state, before)
            is Ramp -> Unit
        }

        // ---- Count-in to a short rep -----------------------------------------------------
        val next = state.session.blocks.getOrNull(state.blockIndex + 1) as? Rep
        val leftHere = state.secondsLeft ?: state.releaseIn
        val currentCounts = block is Rep && countFrom(state.blockIndex, block) != null
        if (next != null && tierOf(next) == CueTier.SHORT && settings.allows(CueTier.SHORT) &&
            leftHere != null && leftHere in 1..COUNT_IN && !currentCounts && block !is Ramp
        ) {
            out += Cue(leftHere.toString(), CueKind.COUNT)
        }
        return out
    }

    private fun repCues(state: SessionState, rep: Rep): List<Cue> {
        val tier = tierOf(rep)
        if (!settings.allows(tier)) return emptyList()
        val i = state.secondsInBlock
        val left = state.secondsLeft ?: return emptyList()
        countFrom(state.blockIndex, rep)?.let { from ->
            if (i > 0 && left in 1..from) return listOf(Cue(left.toString(), CueKind.COUNT))
        }
        return when (tier) {
            CueTier.SHORT -> emptyList()
            CueTier.MID -> when {
                rep.seconds > 60 && left == 30 -> listOf(Cue("30 to go"))
                i == rep.seconds / 2 -> listOf(Cue("Halfway"))
                else -> emptyList()
            }
            CueTier.LONG -> longCues(i, left) +
                if (left == PUSH_AT && rep.kind != RepKind.STEADY) listOf(Cue("Push")) else emptyList()
        }
    }

    /** Warm-up, easy riding and cool-down: only when long enough for the long tier. */
    private fun steadyCues(state: SessionState, seconds: Int): List<Cue> {
        if (seconds <= LONG_OVER_SECONDS || !settings.allows(CueTier.LONG)) return emptyList()
        val left = state.secondsLeft ?: return emptyList()
        return longCues(state.secondsInBlock, left)
    }

    private fun longCues(i: Int, left: Int): List<Cue> = when {
        left == FIVE_MINUTES -> listOf(Cue("5 to go"))
        left == ONE_MINUTE -> listOf(Cue("1 to go"))
        i > 0 && i % FIVE_MINUTES == 0 && left > FIVE_MINUTES -> listOf(Cue("${i / 60} minutes"))
        else -> emptyList()
    }

    private fun gateCues(state: SessionState, before: SessionState?): List<Cue> {
        val out = ArrayList<Cue>()
        val progress = state.recoveryProgress
        if (progress != null && progress >= 0.5 && !state.recovered &&
            state.recoveryPhase == RecoveryPhase.RECOVERING && saidComingDownFor != state.blockIndex
        ) {
            saidComingDownFor = state.blockIndex
            out += Cue("HR coming down")
        }
        val wasRecovered = before?.blockIndex == state.blockIndex && before.recovered
        if (state.recovered && !wasRecovered) {
            out += Cue("Recovered, next rep in ${state.releaseIn}")
        } else if (!state.recovered && state.releaseIn == NEXT_REP_WARNING) {
            out += Cue("Next rep in $NEXT_REP_WARNING")
        }
        return out
    }

    /**
     * Where the closing count starts for a rep, or null for none. Short reps count from 10,
     * or from 5 when another rep of the same set follows straight away or after a short
     * rest (30/15 style); the last of a set counts from 10. Mid reps count from 10.
     */
    private fun countFrom(index: Int, rep: Rep): Int? = when (tierOf(rep)) {
        CueTier.SHORT -> if (repeatedInSet(index, rep)) 5 else 10
        CueTier.MID -> 10
        CueTier.LONG -> null
    }

    private fun repeatedInSet(index: Int, rep: Rep): Boolean {
        val blocks = previous?.session?.blocks ?: return false
        val after = blocks.getOrNull(index + 1)
        val nextRep = when {
            after is Rep -> after
            after is Easy && after.seconds <= SHORT_REST_SECONDS -> blocks.getOrNull(index + 2) as? Rep
            else -> null
        }
        return nextRep != null && nextRep.set == rep.set
    }

    companion object {
        const val COUNT_IN = 3
        const val PUSH_AT = 5
        const val NEXT_REP_WARNING = 10
        const val SHORT_REST_SECONDS = 30
        const val LONG_OVER_SECONDS = 300
        private const val FIVE_MINUTES = 300
        private const val ONE_MINUTE = 60

        fun tierOf(rep: Rep): CueTier = when {
            rep.kind == RepKind.STEADY || rep.seconds > LONG_OVER_SECONDS -> CueTier.LONG
            rep.seconds <= Rep.SHORT_REP_MAX_SECONDS -> CueTier.SHORT
            else -> CueTier.MID
        }
    }
}
