package com.mywatt.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.mywatt.app.data.RiderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Where the connection to the heart-rate strap currently stands. */
sealed class HeartRateState {
    object Idle : HeartRateState()
    data class Connecting(val deviceName: String) : HeartRateState()
    data class Connected(val deviceName: String) : HeartRateState()
    data class Reconnecting(val deviceName: String) : HeartRateState()
}

/** The most recent Heart Rate Measurement, with the time it landed. */
data class HeartRateSample(
    val measurement: HeartRateMeasurement,
    val receivedAtElapsedMs: Long,
)

/**
 * The strap's own GATT connection, fully independent of the trainer's: it has its own
 * state machine and retry logic, so either device can connect first and either can drop
 * without disturbing the other. Scanning is shared and owned by [BluetoothManager], which
 * hands discovered straps in through [connect] and is asked to rescan via [Listener].
 */
@SuppressLint("MissingPermission")
class HeartRateMonitor(
    context: Context,
    private val adapter: () -> BluetoothAdapter?,
    private val handler: Handler,
    private val settings: RiderSettings,
    private val listener: Listener,
) {
    /** Hooks back into the shared pieces that live on [BluetoothManager]. All on main thread. */
    interface Listener {
        /** Services were enumerated; the GATT table can be written to the discovery log. */
        fun onStrapServicesDiscovered(device: ScannedDevice, gatt: BluetoothGatt)

        /** The strap's GATT was closed, for discovery-log housekeeping. */
        fun onStrapClosed()

        /** Direct reconnects have been exhausted; a scan is needed to find the strap again. */
        fun onStrapNeedsScan()
    }

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<HeartRateState>(HeartRateState.Idle)
    val state: StateFlow<HeartRateState> = _state.asStateFlow()

    private val _sample = MutableStateFlow<HeartRateSample?>(null)
    val sample: StateFlow<HeartRateSample?> = _sample.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var connectAttempt = 0
    private var disconnectRequested = false
    private var retryRunnable: Runnable? = null

    /** The strap worn last, restored from settings so it reconnects on launch. */
    var preferredAddress: String? = settings.strapAddress
        private set
    private var preferredName: String = settings.strapName ?: DEFAULT_STRAP_NAME

    /** True while a GATT object exists, i.e. connecting or connected. */
    val isActive: Boolean get() = gatt != null

    fun connect(device: ScannedDevice) {
        val remote: BluetoothDevice = runCatching { adapter()?.getRemoteDevice(device.address) }
            .getOrNull() ?: run {
            Log.w(TAG, "Cannot resolve strap ${device.address}")
            _state.value = HeartRateState.Idle
            return
        }
        preferredAddress = device.address
        preferredName = device.name
        settings.strapAddress = device.address
        settings.strapName = device.name
        disconnectRequested = false
        cancelRetry()
        _state.value = HeartRateState.Connecting(device.name)

        // Never two GATT objects for the same strap: anything still open goes first.
        dropGatt()
        gatt = remote.connectGatt(
            appContext,
            /* autoConnect = */ false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    /** Drops the strap and stops any reconnect attempts. */
    fun disconnect() {
        disconnectRequested = true
        cancelRetry()
        dropGatt()
        _sample.value = null
        _state.value = HeartRateState.Idle
    }

    /** A scan ended without seeing the strap: stop advertising that we are reconnecting. */
    fun giveUp() {
        if (!isActive) _state.value = HeartRateState.Idle
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    /**
     * The one way the strap GATT is ever let go of: disconnect() then close(), so neither
     * the strap nor the Android stack is left holding a dead link. Safe to call with
     * nothing open or with a link that has already dropped.
     */
    private fun dropGatt() {
        val hadGatt = gatt != null
        gatt?.let { release(it) }
        gatt = null
        if (hadGatt) listener.onStrapClosed()
    }

    private fun release(g: BluetoothGatt) {
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS -> {
                    connectAttempt = 0
                    handler.post { runCatching { gatt.discoverServices() } }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    handler.post {
                        if (gatt !== this@HeartRateMonitor.gatt) {
                            // A late callback from a GATT already replaced: just make sure
                            // it is closed, and leave the current connection alone.
                            release(gatt)
                            return@post
                        }
                        dropGatt()
                        _sample.value = null
                        handleDisconnect(status)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Strap service discovery failed ($status)")
                failConnection()
                return
            }
            val characteristic = gatt.getService(HEART_RATE_SERVICE)
                ?.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (characteristic == null) {
                Log.w(TAG, "$preferredName has no Heart Rate Measurement characteristic")
                failConnection()
                return
            }
            handler.post {
                listener.onStrapServicesDiscovered(
                    ScannedDevice(preferredName, preferredAddress ?: "?", DeviceKind.HEART_RATE),
                    gatt,
                )
            }
            enableNotifications(gatt, characteristic)
            _state.value = HeartRateState.Connected(preferredName)
        }

        // API 33+ delivers the payload as an argument; below that it lives on the characteristic.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT) publish(value)
        }

        @Deprecated("Superseded by the three-argument overload on API 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == HEART_RATE_MEASUREMENT) publish(characteristic.value ?: return)
        }
    }

    private fun publish(payload: ByteArray) {
        val parsed = HeartRateParser.parse(payload) ?: return
        _sample.value = HeartRateSample(parsed, SystemClock.elapsedRealtime())
    }

    /** The strap is not usable: drop it cleanly and stop trying. */
    private fun failConnection() {
        handler.post {
            disconnectRequested = true
            dropGatt()
            _sample.value = null
            _state.value = HeartRateState.Idle
        }
    }

    private fun handleDisconnect(status: Int) {
        if (disconnectRequested) {
            _state.value = HeartRateState.Idle
            return
        }
        val address = preferredAddress ?: run {
            _state.value = HeartRateState.Idle
            return
        }
        _state.value = HeartRateState.Reconnecting(preferredName)
        if (connectAttempt < MAX_CONNECT_ATTEMPTS) {
            connectAttempt++
            val device = ScannedDevice(preferredName, address, DeviceKind.HEART_RATE)
            retryRunnable = Runnable { if (!disconnectRequested) connect(device) }
            handler.postDelayed(retryRunnable!!, RECONNECT_DELAY_MS)
            return
        }
        connectAttempt = 0
        listener.onStrapNeedsScan()
    }

    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.w(TAG, "setCharacteristicNotification refused for ${characteristic.uuid}")
        }
        val cccd = characteristic.getDescriptor(CCCD) ?: run {
            Log.w(TAG, "Heart Rate Measurement has no CCCD descriptor")
            return
        }
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = enable
                gatt.writeDescriptor(cccd)
            }
        }
    }

    companion object {
        private const val TAG = "MyWattHeartRate"
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MAX_CONNECT_ATTEMPTS = 3
        const val DEFAULT_STRAP_NAME = "HR strap"

        val HEART_RATE_SERVICE: UUID = UUID.fromString(HeartRateParser.HEART_RATE_SERVICE_UUID)
        private val HEART_RATE_MEASUREMENT: UUID =
            UUID.fromString(HeartRateParser.HEART_RATE_MEASUREMENT_UUID)
        private val CCCD: UUID = UUID.fromString(FTMSParser.CCCD_UUID)
    }
}
