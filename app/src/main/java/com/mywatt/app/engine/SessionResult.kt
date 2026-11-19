package com.mywatt.app.engine

/**
 * What a session produced, built when the cool-down finishes or the rider ends early.
 * Only completed reps are included; a rep cut short by End is dropped.
 */
data class SessionResult(
    val sessionName: String,
    val reps: List<RepResult>,
    /** True if every block ran to the end, false if the rider pressed End. */
    val completed: Boolean,
    /** The recovery setting the session's HR gates used. */
    val recoverySetting: RecoverySetting = RecoverySetting.DEFAULT,
) {
    /** One line summing the session up, e.g. "All 3 reps on target · Recovery 60 / 65 / 72 s". */
    val verdict: String get() = verdict(reps, completed)

    companion object {
        fun of(state: SessionState, completed: Boolean): SessionResult =
            SessionResult(state.session.name, state.reps, completed, state.session.recoverySetting)

        /**
         * The verdict text: how many reps were on target (green), the recovery times in
         * order, which reps' gates timed out, and whether the session was ended early.
         */
        fun verdict(reps: List<RepResult>, completed: Boolean): String {
            if (reps.isEmpty()) return if (completed) "No reps" else "Ended before the first rep"
            val parts = ArrayList<String>()
            val onTarget = reps.count { it.band == Band.GREEN }
            parts += if (onTarget == reps.size) {
                "All ${reps.size} reps on target"
            } else {
                "$onTarget of ${reps.size} reps on target"
            }
            val recoveries = reps.mapNotNull { it.recoverySeconds }
            if (recoveries.isNotEmpty()) parts += "Recovery " + recoveries.joinToString(" / ") + " s"
            val timedOut = reps.filter { it.gateTimedOut }
            if (timedOut.isNotEmpty()) {
                parts += "Gate timed out on rep " + timedOut.joinToString(", ") { it.repNumber.toString() }
            }
            if (!completed) parts += "Ended early"
            return parts.joinToString(" · ")
        }
    }
}
