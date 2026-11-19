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
    /** Library id of the session, empty for one built by hand. */
    val sessionId: String = "",
    /** The FTP the session's targets were worked out from, if it was built from FTP. */
    val ftpWatts: Int? = null,
    /** Which FTP test this was, if any. */
    val ftpTest: FtpTest? = null,
) {
    /** For an FTP test, the estimate it produced; null otherwise or if the test fell short. */
    val ftpEstimate: Int? get() = ftpTest?.estimate(reps)

    /** One line summing the session up, e.g. "All 3 reps on target · Recovery 60 / 65 / 72 s". */
    val verdict: String get() = if (ftpTest != null) testVerdict(ftpTest, reps, completed) else verdict(reps, completed)

    companion object {
        fun of(state: SessionState, completed: Boolean): SessionResult = SessionResult(
            sessionName = state.session.name,
            reps = state.reps,
            completed = completed,
            recoverySetting = state.session.recoverySetting,
            sessionId = state.session.id,
            ftpWatts = state.session.ftpWatts,
            ftpTest = state.session.ftpTest,
        )

        /** The verdict for an FTP test: the estimate and what it was worked out from. */
        fun testVerdict(test: FtpTest, reps: List<RepResult>, completed: Boolean): String {
            val parts = ArrayList<String>()
            val estimate = test.estimate(reps)
            parts += if (estimate != null) "FTP estimate $estimate W" else "No FTP estimate"
            reps.lastOrNull()?.let { last ->
                parts += when (test) {
                    FtpTest.RAMP -> "Last full step ${last.targetWatts} W"
                    FtpTest.TWENTY_MINUTE -> "20 min average ${last.averageWatts} W"
                }
            }
            if (!completed) parts += "Ended early"
            return parts.joinToString(" · ")
        }

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
