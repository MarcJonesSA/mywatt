package com.mywatt.app.session

import com.mywatt.app.bluetooth.GearReading
import com.mywatt.app.engine.Session
import com.mywatt.app.engine.SessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** This second's readings, as the ride screen's 1 Hz loop saw them. */
data class LiveReadout(
    val smoothedWatts: Int?,
    val hrBpm: Int?,
    val hrContactLost: Boolean,
    val gear: GearReading?,
    val cadenceRpm: Int? = null,
)

/**
 * The training session in progress, shared between the ride screen (which owns the
 * Bluetooth link and drives the session from its 1 Hz loop) and the session screen
 * (which only displays it). Process-wide so the two Activities need no plumbing.
 */
object ActiveSession {

    private val _controller = MutableStateFlow<SessionController?>(null)

    /** The session under way or just ended, or null when there is none. */
    val controller: StateFlow<SessionController?> = _controller.asStateFlow()

    private val _live = MutableStateFlow<LiveReadout?>(null)

    /** The latest readings from the ride loop, or null while the bike is not connected. */
    val live: StateFlow<LiveReadout?> = _live.asStateFlow()

    /** True while a session is running or paused, false once it has ended or been cleared. */
    val isRunning: Boolean get() = _controller.value?.let { !it.ended } == true

    /** Set once the rider has answered the offer to save an FTP test's estimate, so it is asked once. */
    var ftpOfferAnswered = false

    fun start(session: Session): SessionController {
        val controller = SessionController(session)
        ftpOfferAnswered = false
        _controller.value = controller
        return controller
    }

    fun clear() {
        _controller.value = null
    }

    fun publishLive(readout: LiveReadout?) {
        _live.value = readout
    }
}
