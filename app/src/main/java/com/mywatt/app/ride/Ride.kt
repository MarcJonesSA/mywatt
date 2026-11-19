package com.mywatt.app.ride

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mywatt.app.bluetooth.BluetoothManager
import com.mywatt.app.bluetooth.ConnectionState
import com.mywatt.app.bluetooth.GearReading
import com.mywatt.app.bluetooth.HeartRateMeasurement
import com.mywatt.app.bluetooth.HeartRateState
import com.mywatt.app.data.PowerSmoother
import com.mywatt.app.data.RideLog
import com.mywatt.app.data.RiderSettings
import com.mywatt.app.data.history.HistoryDatabase
import com.mywatt.app.audio.CueSpeaker
import com.mywatt.app.engine.CuePlanner
import com.mywatt.app.engine.CueSettings
import com.mywatt.app.engine.HeartRateRecovery
import com.mywatt.app.engine.SessionController
import com.mywatt.app.session.ActiveSession
import com.mywatt.app.session.LiveReadout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Everything about the ride that must outlive a screen: the Bluetooth links, the power
 * smoother, heart-rate recovery, the ride log and the 1 Hz loop that feeds the log and the
 * training session. One per process, so rotating the phone or opening the session screen
 * never drops the bike or restarts the CSV. The ride screen creates it and releases it
 * only when the rider really leaves the app (not on a configuration change).
 *
 * All work runs on the main thread, as the Activity's did; file writes hop to IO.
 */
class Ride private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = RiderSettings(appContext)
    val bluetooth = BluetoothManager(appContext)

    /**
     * Rolling average of raw power. Everything the rider looks at for power reads from
     * here; the raw value stays on [BluetoothManager.liveSample] for logging.
     */
    val smoother = PowerSmoother(settings.smoothingSeconds)

    /** 60 s and 120 s heart-rate recovery after each effort, fed from the 1 Hz loop. */
    val recovery = HeartRateRecovery()

    /** Fallback ride clock, used when the trainer does not report elapsed time itself. */
    var connectedAtElapsedMs: Long? = null

    /** The CSV being written for the current connection, and the 1 Hz job feeding it. */
    private var rideLog: RideLog? = null
    private var rideLogJob: Job? = null

    /** RR intervals received since the last CSV row was written. Main thread only. */
    private val pendingRrMs = ArrayList<Int>()

    init {
        observeHeartRate()
        observeLiveSamples()
        observeRideLogging()
        observeConnectionClock()
        observeSessions()
        startSessionClock()
    }

    /** This second's readings, any of which may be null (no bike, no strap, stale data). */
    private class Readings(
        val raw: Int?,
        val smoothed: Int?,
        val cadence: Int?,
        val gear: GearReading?,
        val hr: HeartRateMeasurement?,
    )

    private fun readNow(now: Long): Readings {
        val sample = bluetooth.liveSample.value
        val fresh = sample != null && now - sample.receivedAtElapsedMs <= BluetoothManager.STALE_AFTER_MS
        val data = if (fresh) sample?.data else null
        return Readings(
            raw = data?.instantaneousPowerWatts?.coerceAtLeast(0),
            smoothed = if (fresh) smoother.average(now) else null,
            cadence = data?.instantaneousCadenceRpm?.roundToInt(),
            gear = currentGear(),
            hr = currentHeartRate(now),
        )
    }

    private var cueSpeaker: CueSpeaker? = null
    private var cuePlanner: CuePlanner? = null
    private var cueController: SessionController? = null
    private var cueSettings = CueSettings()

    /** Works out this second's cues for [controller]'s session and plays them unless muted. */
    private fun speakCues(controller: SessionController) {
        if (controller !== cueController) {
            cueController = controller
            cueSettings = RiderSettings(appContext).cueSettings
            cuePlanner = CuePlanner(cueSettings)
            // Made at the first session with cues on, so the voice is warm by the first cue.
            if (cueSettings.enabled && cueSpeaker == null) cueSpeaker = CueSpeaker(appContext)
        }
        val planner = cuePlanner ?: return
        val cues = planner.cues(controller.state.value)
        if (!cueSettings.enabled || controller.ended || controller.paused.value) return
        val speaker = cueSpeaker ?: return
        if (ActiveSession.muted.value) {
            speaker.stop()
            return
        }
        speaker.play(cues, cueSettings.beepOnly)
    }

    /**
     * The training session's clock: a 1 Hz wall-clock tick that runs for the life of the
     * ride whether or not the bike is connected or sending, so a session never freezes on
     * missing data. Each tick feeds whatever readings exist (possibly none) to the session
     * and publishes them for the session screen. Ticks are scheduled against the
     * monotonic clock so they do not drift.
     */
    private fun startSessionClock() {
        scope.launch {
            var next = SystemClock.elapsedRealtime()
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val r = readNow(now)
                ActiveSession.controller.value?.let { controller ->
                    controller.tick(r.smoothed, r.hr?.bpm, r.gear?.gear, r.raw, r.cadence)
                    speakCues(controller)
                }
                ActiveSession.publishLive(
                    LiveReadout(r.smoothed, r.hr?.bpm, r.hr?.contactLost == true, r.gear, r.cadence)
                )
                next += SESSION_TICK_MS
                delay((next - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            }
        }
    }

    /**
     * For each training session: tags the ride log with its library id, and once its
     * result appears, saves it to the history. The save runs as its own job so that
     * leaving the results page straight away does not cut it short.
     */
    private fun observeSessions() {
        scope.launch {
            ActiveSession.controller.collectLatest { controller ->
                if (controller == null) return@collectLatest
                if (!controller.ended) tagLog(controller.session.id)
                val result = controller.result.filterNotNull().first()
                if (!ActiveSession.claimSave(controller)) return@collectLatest
                val startedAt = ActiveSession.startedAtMs
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        HistoryDatabase.get(appContext).dao().insert(result, startedAt, System.currentTimeMillis())
                    }.onFailure { Log.e(TAG, "Could not save session result", it) }
                }
            }
        }
    }

    private fun tagLog(sessionId: String) {
        val log = rideLog ?: return
        if (sessionId.isEmpty()) return
        runCatching { log.tagWithSession(sessionId) }.onFailure { Log.w(TAG, "Could not tag ride log", it) }
    }

    /**
     * The gear to show and log, shared by the screen and the CSV so grey/white always
     * matches gear_confirmed. Disconnected, there is none.
     */
    fun currentGear(): GearReading? =
        if (bluetooth.connectionState.value is ConnectionState.Connected) bluetooth.gear.value else null

    /** The strap's latest measurement, or null when no strap is connected or it has gone quiet. */
    fun currentHeartRate(nowMs: Long): HeartRateMeasurement? {
        if (bluetooth.heartRate.state.value !is HeartRateState.Connected) return null
        val sample = bluetooth.heartRate.sample.value ?: return null
        if (nowMs - sample.receivedAtElapsedMs > BluetoothManager.STALE_AFTER_MS) return null
        return sample.measurement
    }

    private fun observeConnectionClock() {
        scope.launch {
            bluetooth.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected ->
                        if (connectedAtElapsedMs == null) connectedAtElapsedMs = SystemClock.elapsedRealtime()
                    is ConnectionState.Scanning, is ConnectionState.Idle -> connectedAtElapsedMs = null
                    else -> Unit
                }
            }
        }
    }

    /** Accumulates RR intervals from every strap notification for the next CSV row. */
    private fun observeHeartRate() {
        scope.launch {
            bluetooth.heartRate.sample.collect { sample ->
                if (sample != null) pendingRrMs += sample.measurement.rrIntervalsMs
            }
        }
    }

    /**
     * Feeds every raw power reading into the smoother as it lands, independent of the 1 Hz
     * repaint, so the window really does cover the last N seconds of notifications.
     */
    private fun observeLiveSamples() {
        scope.launch {
            bluetooth.liveSample.collect { sample ->
                if (sample == null) {
                    smoother.clear()
                    return@collect
                }
                val raw = sample.data.instantaneousPowerWatts ?: return@collect
                smoother.add(
                    raw.coerceAtLeast(0),
                    sample.receivedAtElapsedMs,
                    sample.data.instantaneousCadenceRpm,
                )
            }
        }
    }

    /**
     * Opens a new CSV every time the connection comes up and closes it when it goes away,
     * so a reconnect after a drop starts a fresh file.
     */
    private fun observeRideLogging() {
        scope.launch {
            bluetooth.connectionState.collect { state ->
                if (state is ConnectionState.Connected) startRideLog() else stopRideLog()
            }
        }
    }

    private fun startRideLog() {
        if (rideLog != null) return
        val log = runCatching { RideLog.start(appContext) }.getOrNull() ?: return
        rideLog = log
        ActiveSession.controller.value?.takeIf { !it.ended }?.let { tagLog(it.session.id) }
        pendingRrMs.clear()
        recovery.reset()
        val startedAt = SystemClock.elapsedRealtime()
        rideLogJob = scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val r = readNow(now)
                val raw = r.raw
                val smoothed = r.smoothed
                val cadence = r.cadence
                val gear = r.gear
                val hr = r.hr
                val rr = pendingRrMs.toList().also { pendingRrMs.clear() }
                val elapsed = (now - startedAt) / 1000L
                // The engine hands back a result only on a completing second: at 60 s one
                // without hrr120, at 120 s one with it. Each lands in its own column.
                val completed = recovery.onSecond(elapsed, hr?.bpm, smoothed)
                val hrr60 = completed?.takeIf { it.hrr120 == null }?.hrr60
                val hrr120 = completed?.hrr120
                // The session runs on its own clock (startSessionClock); the row records
                // which block it is in.
                val session = ActiveSession.controller.value?.takeIf { !it.ended }?.state?.value
                withContext(Dispatchers.IO) {
                    runCatching {
                        log.append(
                            elapsed, raw, smoothed, cadence, gear?.gear, gear?.confirmed == true,
                            hr?.bpm, rr, hrr60, hrr120,
                            session?.blockTag, session?.targetWatts, session?.band?.tag,
                        )
                    }
                }
                delay(LOG_INTERVAL_MS)
            }
        }
    }

    private fun stopRideLog() {
        rideLogJob?.cancel()
        rideLogJob = null
        rideLog?.close()
        rideLog = null
    }

    private fun shutdown() {
        cueSpeaker?.shutdown()
        cueSpeaker = null
        stopRideLog()
        bluetooth.release()
        scope.cancel()
    }

    companion object {
        private const val LOG_INTERVAL_MS = 1_000L
        private const val SESSION_TICK_MS = 1_000L
        private const val TAG = "Ride"

        private var instance: Ride? = null

        /** True once a ride has been created and not yet released. */
        val exists: Boolean get() = instance != null

        /** The ride in progress, or null if none has been created. */
        val current: Ride? get() = instance

        /** The ride in progress, created on first use. Main thread only. */
        fun get(context: Context): Ride = instance ?: Ride(context).also { instance = it }

        /** Drops both links, closes the log and forgets the ride. Main thread only. */
        fun release() {
            instance?.shutdown()
            instance = null
        }
    }
}
