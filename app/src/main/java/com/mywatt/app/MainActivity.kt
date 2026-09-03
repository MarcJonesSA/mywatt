package com.mywatt.app

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
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
import com.mywatt.app.bluetooth.TrainerDevice
import com.mywatt.app.data.PowerZones
import com.mywatt.app.data.RiderSettings
import com.mywatt.app.databinding.ActivityMainBinding
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
    private lateinit var settings: RiderSettings
    private lateinit var bluetooth: BluetoothManager

    private var devicePickerDialog: AlertDialog? = null

    /** Fallback ride clock, used when the trainer does not report elapsed time itself. */
    private var sessionStartedAtElapsedMs: Long? = null

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

        binding.revCounter.setFtp(settings.ftpWatts)
        binding.ftpButton.text = getString(R.string.ftp_button, settings.ftpWatts)
        binding.ftpButton.setOnClickListener { showFtpDialog() }
        binding.connectButton.setOnClickListener { onConnectButtonClicked() }

        renderZone(null)
        observeConnection()
        startDisplayRefresh()
    }

    override fun onDestroy() {
        devicePickerDialog?.dismiss()
        devicePickerDialog = null
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
        if (state is ConnectionState.DevicesFound) showDevicePicker(state.devices)
    }

    private fun showDevicePicker(devices: List<TrainerDevice>) {
        if (devicePickerDialog?.isShowing == true) return
        val labels = devices.map { "${it.name}\n${it.address}" }.toTypedArray()
        devicePickerDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.picker_title)
            .setItems(labels) { _, index -> bluetooth.connect(devices[index]) }
            .setNegativeButton(R.string.ftp_dialog_cancel) { _, _ -> bluetooth.disconnect() }
            .setOnDismissListener { devicePickerDialog = null }
            .show()
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
        val sample = bluetooth.liveSample.value
        val age = sample?.let { SystemClock.elapsedRealtime() - it.receivedAtElapsedMs }
        val fresh = sample != null && age != null && age <= BluetoothManager.STALE_AFTER_MS
        val data = if (fresh) sample?.data else null

        val watts = data?.instantaneousPowerWatts?.coerceAtLeast(0)
        binding.revCounter.setPower(watts)
        renderZone(watts)

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

    private fun formatElapsed(reportedSeconds: Int?): String {
        val seconds = reportedSeconds
            ?: sessionStartedAtElapsedMs?.let {
                ((SystemClock.elapsedRealtime() - it) / 1000L).toInt()
            }
            ?: return getString(R.string.no_reading)
        return String.format(Locale.UK, "%d:%02d", seconds / 60, seconds % 60)
    }

    // -------------------------------------------------------------------- FTP

    private fun showFtpDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_ftp, null)
        val input = view.findViewById<TextInputEditText>(R.id.ftpInput)
        input.setText(settings.ftpWatts.toString())
        input.setSelection(input.text?.length ?: 0)

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
                settings.ftpWatts = entered
                binding.ftpButton.text = getString(R.string.ftp_button, entered)
                binding.revCounter.setFtp(entered)
                renderLiveData()
            }
            .show()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1_000L
        private const val ZONE_CHIP_BACKGROUND_ALPHA = 38
    }
}
