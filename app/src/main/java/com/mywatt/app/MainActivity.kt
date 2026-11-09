package com.mywatt.app

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.content.DialogInterface
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.mywatt.app.bluetooth.BluetoothManager
import com.mywatt.app.bluetooth.ConnectionState
import com.mywatt.app.bluetooth.DeviceKind
import com.mywatt.app.bluetooth.GearReading
import com.mywatt.app.bluetooth.HeartRateMeasurement
import com.mywatt.app.bluetooth.HeartRateState
import com.mywatt.app.bluetooth.ScannedDevice
import com.mywatt.app.data.PowerSmoother
import com.mywatt.app.data.PowerZones
import com.mywatt.app.data.RideLog
import com.mywatt.app.data.RiderSettings
import com.mywatt.app.databinding.ActivityMainBinding
import com.mywatt.app.engine.HeartRateRecovery
import com.mywatt.app.engine.Session
import com.mywatt.app.session.ActiveSession
import com.mywatt.app.session.LiveReadout
import com.mywatt.app.session.SessionActivity
import com.mywatt.app.ui.shareLatestLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The ride screen: live power on the rev counter, cadence and a couple of secondary
 * readouts underneath, all refreshed once a second.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: RiderSettings
    private lateinit var bluetooth: BluetoothManager

    private var devicePickerDialog: AlertDialog? = null
    private var pickerDevices: List<ScannedDevice> = emptyList()
    private val pickerRows = ArrayList<View>()

    /** 60 s and 120 s heart-rate recovery after each effort, fed from the 1 Hz ride-log loop. */
    private val recovery = HeartRateRecovery()

    /**
     * Rolling average of raw power. Everything the rider looks at for power (needle, main
     * watts number, zone chip, any future target comparison) reads from here; the raw
     * value stays on [BluetoothManager.liveSample] for logging.
     */
    private lateinit var smoother: PowerSmoother

    /** Fallback ride clock, used when the trainer does not report elapsed time itself. */
    private var sessionStartedAtElapsedMs: Long? = null

    /** The CSV being written for the current connection, and the 1 Hz job feeding it. */
    private var rideLog: RideLog? = null
    private var rideLogJob: Job? = null

    /** RR intervals received since the last CSV row was written. Main thread only. */
    private val pendingRrMs = ArrayList<Int>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            beginConnect()
        } else {
            binding.statusText.text = getString(R.string.permission_required)
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            beginConnect()
        } else {
            binding.statusText.text = getString(R.string.bluetooth_disabled)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Nobody wants the screen dozing off mid-interval.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = RiderSettings(this)
        bluetooth = BluetoothManager(this)
        smoother = PowerSmoother(settings.smoothingSeconds)

        binding.revCounter.setFtp(settings.ftpWatts)
        binding.ftpButton.text = getString(R.string.ftp_button, settings.ftpWatts)
        binding.ftpButton.setOnClickListener { showFtpDialog() }
        binding.shareButton.setOnClickListener { shareLatestLogs(this) }
        binding.connectButton.setOnClickListener { onConnectButtonClicked() }
        binding.startSessionButton.setOnClickListener { onStartSessionClicked() }

        renderZone(null)
        observeConnection()
        observeLiveSamples()
        observeHeartRate()
        observeRideLogging()
        startDisplayRefresh()

        // Reconnect to the bike and strap from last time without a tap. First-run users
        // (nothing remembered, or permissions not yet granted) still start from the button.
        if (bluetooth.hasRememberedDevices() &&
            bluetooth.hasPermissions() &&
            bluetooth.isBluetoothEnabled()
        ) {
            beginConnect()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.startSessionButton.setText(
            if (ActiveSession.isRunning) R.string.action_resume_session else R.string.action_start_session
        )
    }

    override fun onDestroy() {
        devicePickerDialog?.dismiss()
        devicePickerDialog = null
        stopRideLog()
        bluetooth.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------- connecting

    private fun onConnectButtonClicked() {
        when (bluetooth.connectionState.value) {
            is ConnectionState.Idle, is ConnectionState.Error -> beginConnect()
            else -> {
                sessionStartedAtElapsedMs = null
                bluetooth.disconnect()
            }
        }
    }

    private fun beginConnect() {
        if (!bluetooth.hasPermissions()) {
            permissionLauncher.launch(BluetoothManager.requiredPermissions())
            return
        }
        if (!bluetooth.isBluetoothEnabled()) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        sessionStartedAtElapsedMs = null
        bluetooth.startScan()
    }

    private fun observeConnection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bluetooth.connectionState.collect { state -> renderConnectionState(state) }
            }
        }
        // The picker is driven separately from the trainer state, because it may be asking
        // only about a strap while the bike is already connected.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bluetooth.pendingChoices.collect { devices ->
                    if (devices.isEmpty()) {
                        devicePickerDialog?.dismiss()
                    } else {
                        showDevicePicker(devices)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bluetooth.heartRate.state.collect { refreshPickerRows() }
            }
        }
    }

    /** Accumulates RR intervals from every strap notification for the next CSV row. */
    private fun observeHeartRate() {
        lifecycleScope.launch {
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
        lifecycleScope.launch {
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
     * The gear to show and log, shared by the screen and the CSV so grey/white always matches
     * gear_confirmed. While connected this is the bike's reading: gear 7 unconfirmed until
     * the first notification, then whatever the Atom sends. Disconnected, there is none.
     */
    private fun currentGear(): GearReading? =
        if (bluetooth.connectionState.value is ConnectionState.Connected) bluetooth.gear.value else null

    /** The strap's latest measurement, or null when no strap is connected or it has gone quiet. */
    private fun currentHeartRate(nowMs: Long): HeartRateMeasurement? {
        if (bluetooth.heartRate.state.value !is HeartRateState.Connected) return null
        val sample = bluetooth.heartRate.sample.value ?: return null
        if (nowMs - sample.receivedAtElapsedMs > BluetoothManager.STALE_AFTER_MS) return null
        return sample.measurement
    }

    private fun renderConnectionState(state: ConnectionState) {
        val connected = state is ConnectionState.Connected
        val busy = state is ConnectionState.Scanning ||
            state is ConnectionState.Connecting ||
            state is ConnectionState.Reconnecting

        binding.statusText.text = when (state) {
            is ConnectionState.Idle -> getString(R.string.status_idle)
            is ConnectionState.Scanning -> getString(R.string.status_scanning)
            is ConnectionState.DevicesFound -> getString(R.string.picker_title)
            is ConnectionState.Connecting -> getString(R.string.status_connecting, state.deviceName)
            is ConnectionState.Connected -> getString(R.string.status_connected, state.deviceName)
            is ConnectionState.Reconnecting ->
                getString(R.string.status_reconnecting, state.deviceName)
            is ConnectionState.Error -> state.message
        }
        val hint = (state as? ConnectionState.Error)?.hint
        binding.statusHint.text = hint ?: ""
        binding.statusHint.visibility = if (hint != null) View.VISIBLE else View.GONE
        binding.statusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (state is ConnectionState.Error) R.color.zone_6 else R.color.text_secondary,
            )
        )

        val dotColour = when {
            connected -> R.color.status_connected
            busy -> R.color.zone_4
            state is ConnectionState.Error -> R.color.zone_6
            else -> R.color.status_idle
        }
        binding.statusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, dotColour)

        binding.connectButton.setText(
            when {
                busy -> R.string.action_searching
                connected || state is ConnectionState.DevicesFound -> R.string.action_disconnect
                else -> R.string.action_connect
            }
        )

        if (connected && sessionStartedAtElapsedMs == null) {
            sessionStartedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        refreshPickerRows()
    }

    // ---------------------------------------------------------- device picker

    private enum class PickerStatus { AVAILABLE, CONNECTING, CONNECTED }

    /**
     * Lists every bike and strap the scan found. Tapping a row connects it and the row
     * stays, ticked once the device is up. The dialog closes on Done, or by itself once
     * both a bike and a strap are connected.
     */
    private fun showDevicePicker(devices: List<ScannedDevice>) {
        if (devicePickerDialog?.isShowing == true && pickerDevices == devices) {
            refreshPickerRows()
            return
        }
        devicePickerDialog?.dismiss()

        val view = layoutInflater.inflate(R.layout.dialog_device_picker, null)
        val list = view.findViewById<LinearLayout>(R.id.deviceList)
        pickerDevices = devices
        pickerRows.clear()
        devices.forEach { device ->
            val row = layoutInflater.inflate(R.layout.item_device, list, false)
            row.findViewById<TextView>(R.id.deviceName).text = device.name
            row.findViewById<TextView>(R.id.deviceKind).setText(
                when (device.kind) {
                    DeviceKind.TRAINER -> R.string.picker_kind_bike
                    DeviceKind.HEART_RATE -> R.string.picker_kind_hr
                }
            )
            row.setOnClickListener {
                if (pickerStatus(device) == PickerStatus.AVAILABLE) bluetooth.connect(device)
                refreshPickerRows()
            }
            list.addView(row)
            pickerRows += row
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.picker_title)
            .setView(view)
            .setPositiveButton(R.string.picker_done) { _, _ -> bluetooth.dismissChoices() }
            .setOnCancelListener { bluetooth.dismissChoices() }
            .create()
        // Dismiss callbacks arrive asynchronously, so only the current dialog may clear state.
        dialog.setOnDismissListener { dismissed: DialogInterface ->
            if (devicePickerDialog === dismissed) {
                devicePickerDialog = null
                pickerDevices = emptyList()
                pickerRows.clear()
            }
        }
        devicePickerDialog = dialog
        dialog.show()
        refreshPickerRows()
    }

    private fun pickerStatus(device: ScannedDevice): PickerStatus = when (device.kind) {
        DeviceKind.TRAINER -> {
            if (device.address != bluetooth.trainerAddress) {
                PickerStatus.AVAILABLE
            } else when (bluetooth.connectionState.value) {
                is ConnectionState.Connected -> PickerStatus.CONNECTED
                is ConnectionState.Connecting, is ConnectionState.Reconnecting -> PickerStatus.CONNECTING
                else -> PickerStatus.AVAILABLE
            }
        }
        DeviceKind.HEART_RATE -> {
            if (device.address != bluetooth.heartRate.preferredAddress) {
                PickerStatus.AVAILABLE
            } else when (bluetooth.heartRate.state.value) {
                is HeartRateState.Connected -> PickerStatus.CONNECTED
                is HeartRateState.Connecting, is HeartRateState.Reconnecting -> PickerStatus.CONNECTING
                else -> PickerStatus.AVAILABLE
            }
        }
    }

    /** Repaints each row's hint and tick, and closes the picker once both devices are up. */
    private fun refreshPickerRows() {
        if (devicePickerDialog == null) return
        pickerRows.forEachIndexed { index, row ->
            val device = pickerDevices.getOrNull(index) ?: return@forEachIndexed
            val status = pickerStatus(device)
            row.findViewById<TextView>(R.id.deviceHint).setText(
                when (status) {
                    PickerStatus.AVAILABLE -> R.string.picker_tap_to_connect
                    PickerStatus.CONNECTING -> R.string.picker_connecting
                    PickerStatus.CONNECTED -> R.string.picker_connected
                }
            )
            row.findViewById<View>(R.id.deviceTick).visibility =
                if (status == PickerStatus.CONNECTED) View.VISIBLE else View.INVISIBLE
        }
        val bikeUp = bluetooth.connectionState.value is ConnectionState.Connected
        val strapUp = bluetooth.heartRate.state.value is HeartRateState.Connected
        if (bikeUp && strapUp) bluetooth.dismissChoices()
    }

    // ------------------------------------------------------------ live readout

    /** Repaints every readout once a second for as long as the screen is in front. */
    private fun startDisplayRefresh() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    renderLiveData()
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    private fun renderLiveData() {
        val now = SystemClock.elapsedRealtime()
        val sample = bluetooth.liveSample.value
        val age = sample?.let { now - it.receivedAtElapsedMs }
        val fresh = sample != null && age != null && age <= BluetoothManager.STALE_AFTER_MS
        val data = if (fresh) sample?.data else null

        // Smoothed power drives the needle and the zone chip; cadence below is left raw.
        val watts = if (fresh) smoother.average(now) else null
        binding.revCounter.setPower(watts)
        renderZone(watts)

        // Gear comes from the Wattbike custom characteristic (see WattbikeGearParser), not
        // from FTMS. It only updates on a shift, so it is not blanked by the stale rule. The
        // assumed start-of-session gear is drawn in the secondary colour until confirmed.
        val gear = currentGear()
        binding.gearValue.text = gear
            ?.let { getString(R.string.gear_value, it.gear) }
            ?: getString(R.string.gear_unknown)
        binding.gearValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (gear?.confirmed == true) R.color.text_primary else R.color.text_secondary,
            )
        )

        // Heart rate: "HR --" without a strap or a fresh reading, dimmed when the strap has
        // lost skin contact.
        val hr = currentHeartRate(now)
        binding.hrValue.text = hr
            ?.let { getString(R.string.hr_value, it.bpm) }
            ?: getString(R.string.hr_unknown)
        binding.hrValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (hr != null && !hr.contactLost) R.color.text_primary else R.color.text_secondary,
            )
        )

        binding.recoveryValue.text = recoveryText()

        binding.cadenceValue.text = data?.instantaneousCadenceRpm
            ?.roundToInt()?.toString() ?: getString(R.string.no_reading)

        binding.speedValue.text = data?.instantaneousSpeedKph?.let {
            getString(R.string.speed_value, String.format(Locale.UK, "%.1f", it))
        } ?: getString(R.string.no_reading)

        binding.elapsedValue.text = formatElapsed(data?.elapsedTimeSeconds)

        // A connected trainer that has gone quiet means the rider has stopped pedalling.
        val state = bluetooth.connectionState.value
        if (state is ConnectionState.Connected) {
            binding.statusText.text = if (fresh) {
                getString(R.string.status_connected, state.deviceName)
            } else {
                getString(R.string.status_waiting_for_data)
            }
        }
    }

    private fun renderZone(watts: Int?) {
        if (watts == null) {
            binding.zoneChip.text = getString(R.string.no_reading)
            binding.zoneChip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.zoneChip.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.surface_variant)
            return
        }
        val ftp = settings.ftpWatts
        val zone = PowerZones.zoneFor(watts, ftp)
        val colour = ContextCompat.getColor(this, zone.colorRes)
        binding.zoneChip.text =
            getString(R.string.zone_summary, zone.number, zone.label, zone.rangeLabel(ftp))
        binding.zoneChip.setTextColor(colour)
        binding.zoneChip.backgroundTintList = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(colour, ZONE_CHIP_BACKGROUND_ALPHA)
        )
    }

    /**
     * Live during the first 60 s of a window ("Recovery · HR 146 · 23 s"), then the 60 s
     * drop while the window runs on, then both drops once 120 s is in. Outside a window
     * the last completed reading stays until the next effort replaces it.
     */
    private fun recoveryText(): String {
        val progress = recovery.progress.value
        val latest = recovery.latest.value
        return when {
            progress != null && progress.secondsSinceEnd < HeartRateRecovery.SHORT_WINDOW_SECONDS ->
                getString(
                    R.string.recovery_live,
                    progress.hrBpm?.toString() ?: getString(R.string.no_reading),
                    progress.secondsSinceEnd,
                )
            latest == null -> getString(R.string.recovery_unknown)
            latest.hrr120 != null ->
                getString(R.string.recovery_both, formatRecovery(latest.hrr60), formatRecovery(latest.hrr120))
            else -> getString(R.string.recovery_value, formatRecovery(latest.hrr60))
        }
    }

    /** A drop of 13 bpm reads "-13"; the rare rise reads "+5". */
    private fun formatRecovery(drop: Int): String =
        if (drop >= 0) "-$drop" else "+${-drop}"

    private fun formatElapsed(reportedSeconds: Int?): String {
        val seconds = reportedSeconds
            ?: sessionStartedAtElapsedMs?.let {
                ((SystemClock.elapsedRealtime() - it) / 1000L).toInt()
            }
            ?: return getString(R.string.no_reading)
        return String.format(Locale.UK, "%d:%02d", seconds / 60, seconds % 60)
    }

    // --------------------------------------------------------------- ride log

    /**
     * Opens a new CSV every time the connection comes up and closes it when it goes away,
     * so a reconnect after a drop starts a fresh file. Runs for the life of the Activity
     * rather than only while it is visible, so a backgrounded ride keeps logging.
     */
    private fun observeRideLogging() {
        lifecycleScope.launch {
            bluetooth.connectionState.collect { state ->
                if (state is ConnectionState.Connected) startRideLog() else stopRideLog()
            }
        }
    }

    private fun startRideLog() {
        if (rideLog != null) return
        val log = runCatching { RideLog.start(this) }.getOrNull() ?: return
        rideLog = log
        pendingRrMs.clear()
        recovery.reset()
        val startedAt = SystemClock.elapsedRealtime()
        rideLogJob = lifecycleScope.launch {
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
                controller?.tick(smoothed, hr?.bpm, gear?.gear)
                val session = controller?.takeIf { !it.ended }?.state?.value
                ActiveSession.publishLive(LiveReadout(smoothed, hr?.bpm, hr?.contactLost == true, gear))
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

    // ------------------------------------------------------- training session

    /**
     * Opens the session screen. A session already under way is simply shown again;
     * otherwise a small dialog asks for the rep target, then starts Three by One.
     */
    private fun onStartSessionClicked() {
        if (ActiveSession.isRunning) {
            startActivity(Intent(this, SessionActivity::class.java))
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_target, null)
        val input = view.findViewById<TextInputEditText>(R.id.targetInput)
        input.setText(settings.sessionTargetWatts.toString())
        input.setSelection(input.text?.length ?: 0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.session_dialog_title)
            .setMessage(R.string.session_dialog_message)
            .setView(view)
            .setNegativeButton(R.string.ftp_dialog_cancel, null)
            .setPositiveButton(R.string.session_start) { _, _ ->
                val target = input.text?.toString()?.trim()?.toIntOrNull()
                if (target == null || target !in RiderSettings.MIN_TARGET_WATTS..RiderSettings.MAX_TARGET_WATTS) {
                    Toast.makeText(
                        this,
                        getString(R.string.target_invalid, RiderSettings.MIN_TARGET_WATTS, RiderSettings.MAX_TARGET_WATTS),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setPositiveButton
                }
                settings.sessionTargetWatts = target
                ActiveSession.start(Session.threeByOne(target))
                startActivity(Intent(this, SessionActivity::class.java))
            }
            .show()
    }

    // -------------------------------------------------------------------- FTP

    private fun showFtpDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_ftp, null)
        val input = view.findViewById<TextInputEditText>(R.id.ftpInput)
        input.setText(settings.ftpWatts.toString())
        input.setSelection(input.text?.length ?: 0)
        val smoothingInput = view.findViewById<TextInputEditText>(R.id.smoothingInput)
        smoothingInput.setText(settings.smoothingSeconds.toString())

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ftp_dialog_title)
            .setMessage(
                getString(
                    R.string.ftp_dialog_message,
                    RiderSettings.MIN_FTP,
                    RiderSettings.MAX_FTP,
                )
            )
            .setView(view)
            .setNegativeButton(R.string.ftp_dialog_cancel, null)
            .setPositiveButton(R.string.ftp_dialog_save) { _, _ ->
                val entered = input.text?.toString()?.trim()?.toIntOrNull()
                if (entered == null || entered !in RiderSettings.MIN_FTP..RiderSettings.MAX_FTP) {
                    Toast.makeText(
                        this,
                        getString(
                            R.string.ftp_invalid,
                            RiderSettings.MIN_FTP,
                            RiderSettings.MAX_FTP,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setPositiveButton
                }
                val smoothing = smoothingInput.text?.toString()?.trim()?.toIntOrNull()
                if (smoothing == null ||
                    smoothing !in RiderSettings.MIN_SMOOTHING_SECONDS..RiderSettings.MAX_SMOOTHING_SECONDS
                ) {
                    Toast.makeText(
                        this,
                        getString(
                            R.string.smoothing_invalid,
                            RiderSettings.MIN_SMOOTHING_SECONDS,
                            RiderSettings.MAX_SMOOTHING_SECONDS,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setPositiveButton
                }
                settings.ftpWatts = entered
                settings.smoothingSeconds = smoothing
                smoother.windowSeconds = smoothing
                binding.ftpButton.text = getString(R.string.ftp_button, entered)
                binding.revCounter.setFtp(entered)
                renderLiveData()
            }
            .show()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1_000L
        private const val LOG_INTERVAL_MS = 1_000L
        private const val ZONE_CHIP_BACKGROUND_ALPHA = 38
    }
}
