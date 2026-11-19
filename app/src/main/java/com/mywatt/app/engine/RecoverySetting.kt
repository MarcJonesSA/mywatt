package com.mywatt.app.engine

/**
 * How demanding the heart-rate gate is, chosen by the rider to suit their fitness.
 *
 * [releaseFraction] is how much of a rep's HR rise must be recovered before the next block
 * starts: 0.50 means HR must fall back halfway from the peak to the rep's starting HR.
 * [minRecoverySeconds] is the shortest recovery allowed even when HR is already there, and
 * [timeoutSeconds] the longest before the gate opens anyway (and the rep is marked as
 * timed out). Fitter riders recover faster, so they are asked to recover more of the rise
 * but are let go sooner.
 */
enum class RecoverySetting(
    val label: String,
    val releaseFraction: Double,
    val minRecoverySeconds: Int,
    val timeoutSeconds: Int,
) {
    Building("Building", 0.50, 60, 240),
    Intermediate("Intermediate", 0.60, 45, 240),
    Trained("Trained", 0.70, 30, 240),
    Peak("Peak", 0.80, 30, 240);

    companion object {
        val DEFAULT = Building

        /** The setting stored under [name], or [DEFAULT] for anything unrecognised. */
        fun fromName(name: String?): RecoverySetting = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
