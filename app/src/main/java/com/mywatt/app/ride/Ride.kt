package com.mywatt.app.ride

import android.content.Context
import android.os.SystemClock
import com.mywatt.app.bluetooth.BluetoothManager
import com.mywatt.app.bluetooth.ConnectionState
import com.mywatt.app.bluetooth.GearReading
import com.mywatt.app.bluetooth.HeartRateMeasurement
import com.mywatt.app.bluetooth.HeartRateState
import com.mywatt.app.data.PowerSmoother
import com.mywatt.app.data.RideLog
import com.mywatt.app.data.RiderSettings
import com.mywatt.app.engine.HeartRateRecovery
import com.mywatt.app.session.ActiveSession
import com.mywatt.app.session.LiveReadout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        pendingRrMs.clear()
        recovery.reset()
        val startedAt = SystemClock.elapsedRealtime()
        rideLogJob = scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val sample = bluetooth.liveSample.value
                val fresh = sample != null && now - sample.receivedAtElapsedMs <= BluetoothManager.STALE_AFTER_MS
                val data = if (fresh) sample?.data else null
                val raw = data?.instantaneousPowerWatts?.coerceAtLeast(0)
                val smoothed = if (fresh) smoother.average(now) else null
                val cadence = data?.instantaneousCadenceRpm?.roundToInt()
                val gear = currentGear()
                val hr = currentHeartRate(now)
                val rr = pendingRrMs.toList().also { pendingRrMs.clear() }
                val elapsed = (now - startedAt) / 1000L
                // The engine hands back a result only on a completing second: at 60 s one
                // without hrr120, at 120 s one with it. Each lands in its own column.
                val completed = recovery.onSecond(elapsed, hr?.bpm, smoothed)
                val hrr60 = completed?.takeIf { it.hrr120 == null }?.hrr60
                val hrr120 = completed?.hrr120
                // The training session, if one is running, is driven from this same loop
                // and the row records which block it is in.
                val controller = ActiveSession.controller.value
                controller?.tick(smoothed, hr?.bpm, gear?.gear, raw, cadence)
                val session = controller?.takeIf { !it.ended }?.state?.value
                ActiveSession.publishLive(LiveReadout(smoothed, hr?.bpm, hr?.contactLost == true, gear, cadence))
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
        ActiveSession.publishLive(null)
    }

    private fun shutdown() {
        stopRideLog()
        bluetooth.release()
        scope.cancel()
    }

    companion object {
        private const val LOG_INTERVAL_MS = 1_000L

        private var instance: Ride? = null

        /** True once a ride has been created and not yet released. */
        val exists: Boolean get() = instance != null

        /** The ride in progress, created on first use. Main thread only. */
        fun get(context: Context): Ride = instance ?: Ride(context).also { instance = it }

        /** Drops both links, closes the log and forgets the ride. Main thread only. */
        fun release() {
            instance?.shutdown()
            instance = null
        }
    }
}
