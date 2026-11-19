package com.mywatt.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns a [SessionRunner] for one ride of a session and adds the two things the screen
 * needs around it: pause and end. Pure Kotlin.
 *
 * The runner's clock is this controller's own count of unpaused seconds, so pausing
 * freezes every block where it stands rather than letting the ride clock run on. [tick]
 * once a second from the ride loop; a tick while paused or after the session has ended
 * does nothing.
 */
class SessionController(val session: Session) {

    private val runner = SessionRunner(session)

    /** The live session state; see [SessionRunner.state]. */
    val state: StateFlow<SessionState> get() = runner.state

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _result = MutableStateFlow<SessionResult?>(null)

    /** Set once, when the cool-down completes or [end] is called. */
    val result: StateFlow<SessionResult?> = _result.asStateFlow()

    private var nextSecond = 0L

    val ended: Boolean get() = _result.value != null

    /** Feeds one second of ride data, unless paused or ended. See [SessionRunner.onSecond]. */
    fun tick(
        smoothedWatts: Int?,
        hrBpm: Int?,
        gear: Int?,
        rawWatts: Int? = smoothedWatts,
        cadenceRpm: Int? = null,
    ) {
        if (ended || _paused.value) return
        runner.onSecond(nextSecond++, smoothedWatts, hrBpm, gear, rawWatts, cadenceRpm)
        if (runner.state.value.finished) {
            _result.value = SessionResult.of(runner.state.value, completed = true)
        }
    }

    fun setPaused(paused: Boolean) {
        if (!ended) _paused.value = paused
    }

    fun togglePause() = setPaused(!_paused.value)

    /** Ends the session now with the reps completed so far. */
    fun end() {
        if (ended) return
        _paused.value = false
        _result.value = SessionResult.of(runner.state.value, completed = runner.state.value.finished)
    }
}
