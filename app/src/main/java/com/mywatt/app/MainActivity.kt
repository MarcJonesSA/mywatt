package com.mywatt.app

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.content.DialogInterface
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mywatt.app.bluetooth.BluetoothManager
import com.mywatt.app.bluetooth.ConnectionState
import com.mywatt.app.bluetooth.DeviceKind
import com.mywatt.app.bluetooth.GearReading
import com.mywatt.app.bluetooth.HeartRateMeasurement
import com.mywatt.app.bluetooth.HeartRateState
import com.mywatt.app.bluetooth.ScannedDevice
import com.mywatt.app.data.PowerSmoother
import com.mywatt.app.data.PowerZones
import com.mywatt.app.data.RiderSettings
import com.mywatt.app.databinding.ActivityMainBinding
import com.mywatt.app.data.RiderProfile
import com.mywatt.app.debug.SelfTest
import com.mywatt.app.profile.OnboardingActivity
import com.mywatt.app.engine.HeartRateRecovery
import com.mywatt.app.ride.Ride
import com.mywatt.app.session.ActiveSession
import com.mywatt.app.session.SessionActivity
import com.mywatt.app.ui.padForSystemBars
import com.mywatt.app.ui.showSettingsDialog
import com.mywatt.app.history.HistoryActivity
import com.mywatt.app.library.LibraryActivity
import com.mywatt.app.ui.shareLatestLogs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The ride screen: live power on the rev counter, cadence and a couple of secondary
 * readouts underneath, all refreshed once a second.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** The ride itself (Bluetooth, smoother, log); lives outside this Activity so rotation keeps it. */
    private lateinit var ride: Ride
    private val settings: RiderSettings get() = ride.settings
    private val bluetooth: BluetoothManager get() = ride.bluetooth
    private val smoother: PowerSmoother get() = ride.smoother
    private val recovery: HeartRateRecovery get() = ride.recovery

    private var devicePickerDialog: AlertDialog? = null
    private var pickerDevices: List<ScannedDevice> = emptyList()
    private val pickerRows = ArrayList<View>()

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        // Nobody wants the screen dozing off mid-interval.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val firstScreen = !Ride.exists
        ride = Ride.get(this)

        binding.revCounter.setFtp(settings.ftpWatts)
        binding.ftpButton.text = getString(R.string.ftp_button, settings.ftpWatts)
        binding.ftpButton.setOnClickListener { showFtpDialog() }
        binding.shareButton.setOnClickListener { shareLatestLogs(this) }
        binding.historyButton.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        binding.connectButton.setOnClickListener { onConnectButtonClicked() }
        binding.startSessionButton.setOnClickListener { onStartSessionClicked() }

        renderZone(null)
        observeConnection()
        startDisplayRefresh()
        binding.mainScroll?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitDialAboveFold() }

        // Debug builds only: adb can start a check of the history and ride-log plumbing.
        // First launch: the two entry questions, once.
        val selfTest = BuildConfig.DEBUG && intent?.getStringExtra(SelfTest.EXTRA) != null
        if (savedInstanceState == null && !selfTest && !RiderProfile(this).entryQuestionsDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        if (BuildConfig.DEBUG && savedInstanceState == null &&
            intent?.getStringExtra(SelfTest.EXTRA) == "history"
        ) {
            SelfTest.run(this)
        }

        // Reconnect to the bike and strap from last time without a tap, but only on a fresh
        // start: after a rotation the ride is already up. First-run users (nothing
        // remembered, or permissions not yet granted) still start from the button.
        if (firstScreen &&
            bluetooth.hasRememberedDevices() &&
            bluetooth.hasPermissions() &&
            bluetooth.isBluetoothEnabled()
        ) {
            beginConnect()
        }
    }

    override fun onResume() {
        super.onResume()
        // A test result may have changed FTP while the session screen was on top.
        binding.ftpButton.text = getString(R.string.ftp_button, settings.ftpWatts)
        binding.revCounter.setFtp(settings.ftpWatts)
        binding.startSessionButton.setText(
            if (ActiveSession.isRunning) R.string.action_resume_session else R.string.action_start_session
        )
    }

    override fun onDestroy() {
        devicePickerDialog?.dismiss()
        devicePickerDialog = null
        // A rotation recreates this screen; only a real exit ends the ride.
        if (isFinishing && !isChangingConfigurations) Ride.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------- connecting

    private fun onConnectButtonClicked() {
        when (bluetooth.connectionState.value) {
            is ConnectionState.Idle, is ConnectionState.Error -> beginConnect()
            else -> {
                ride.connectedAtElapsedMs = null
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
        ride.connectedAtElapsedMs = null
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

    private fun currentGear(): GearReading? = ride.currentGear()

    private fun currentHeartRate(nowMs: Long): HeartRateMeasurement? = ride.currentHeartRate(nowMs)

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

    /**
     * Portrait only: caps the dial so that gear, HR and the recovery line under it still
     * fit in the visible part of the scroll view. On a tall screen the cap is above the
     * dial's natural size and changes nothing; on a short one the dial draws smaller.
     */
    private fun fitDialAboveFold() {
        val scroll = binding.mainScroll ?: return
        val viewport = scroll.height - scroll.paddingTop - scroll.paddingBottom
        if (viewport <= 0) return
        val below = listOf(binding.gearValue, binding.hrValue, binding.recoveryValue).sumOf { view ->
            val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
            view.height + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
        }
        val dialMargins = binding.revCounter.layoutParams as? ViewGroup.MarginLayoutParams
        val dialMarginTop = dialMargins?.topMargin ?: 0
        val minimum = (MIN_DIAL_HEIGHT_DP * resources.displayMetrics.density).toInt()
        val cap = (viewport - below - dialMarginTop).coerceAtLeast(minimum)
        // Posted: changing the cap requests a layout, which must not happen inside this one.
        if (cap != binding.revCounter.maxDialHeight) binding.revCounter.post { binding.revCounter.maxDialHeight = cap }
    }

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
            ?: ride.connectedAtElapsedMs?.let {
                ((SystemClock.elapsedRealtime() - it) / 1000L).toInt()
            }
            ?: return getString(R.string.no_reading)
        return String.format(Locale.UK, "%d:%02d", seconds / 60, seconds % 60)
    }

    // ------------------------------------------------------- training session

    /**
     * Opens the session screen if a session is under way, otherwise the session library
     * to pick one.
     */
    private fun onStartSessionClicked() {
        if (ActiveSession.isRunning) {
            startActivity(Intent(this, SessionActivity::class.java))
        } else {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
    }

    // --------------------------------------------------------------- settings

    private fun showFtpDialog() {
        showSettingsDialog(this, settings) {
            binding.ftpButton.text = getString(R.string.ftp_button, settings.ftpWatts)
            binding.revCounter.setFtp(settings.ftpWatts)
            renderLiveData()
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1_000L

        /** The dial never shrinks below this, however short the screen. */
        private const val MIN_DIAL_HEIGHT_DP = 140
        private const val ZONE_CHIP_BACKGROUND_ALPHA = 38
    }
}
