package com.mywatt.app.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.mywatt.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** A trainer discovered during a scan. */
data class TrainerDevice(
    val name: String,
    val address: String,
)

/** The most recent notification received from the trainer, with the time it landed. */
data class LiveSample(
    val data: IndoorBikeData,
    val receivedAtElapsedMs: Long,
)

/** Where the connection to the trainer currently stands. */
sealed class ConnectionState {
    object Idle : ConnectionState()
    object Scanning : ConnectionState()

    /** A scan finished with several candidates and needs the rider to pick one. */
    data class DevicesFound(val devices: List<TrainerDevice>) : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val deviceName: String) : ConnectionState()
    data class Reconnecting(val deviceName: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Owns the BLE lifecycle for the trainer: scanning, connecting, enabling Indoor Bike Data
 * notifications and turning them into [LiveSample]s.
 *
 * All callbacks are marshalled onto the main thread, so [connectionState] and [liveSample]
 * are safe to collect straight from an Activity.
 */
@SuppressLint("MissingPermission")
class BluetoothManager(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val systemBluetooth: android.bluetooth.BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = systemBluetooth?.adapter

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _liveSample = MutableStateFlow<LiveSample?>(null)
    val liveSample: StateFlow<LiveSample?> = _liveSample.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var connectAttempt = 0

    /** Set when the rider taps disconnect, so we do not immediately reconnect. */
    private var disconnectRequested = false

    /** Address of the trainer we are riding, used to reconnect without asking again. */
    private var preferredAddress: String? = null
    private var preferredName: String = DEFAULT_DEVICE_NAME

    private val discovered = LinkedHashMap<String, TrainerDevice>()

    // ---------------------------------------------------------------- scanning

    /** True when the runtime permissions needed to scan and connect have been granted. */
    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /**
     * Starts a BLE scan for fitness machines. Connects automatically to the trainer used
     * last time, or to the first device that looks like a Wattbike; otherwise the discovered
     * devices are published as [ConnectionState.DevicesFound] when the scan window closes.
     */
    fun startScan() {
        if (scanning) return
        disconnectRequested = false

        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null) {
            _connectionState.value =
                ConnectionState.Error(appContext.getString(R.string.error_no_adapter))
            return
        }
        if (!isBluetoothEnabled() || scanner == null) {
            _connectionState.value =
                ConnectionState.Error(appContext.getString(R.string.bluetooth_disabled))
            return
        }
        if (!hasPermissions()) {
            _connectionState.value =
                ConnectionState.Error(appContext.getString(R.string.permission_required))
            return
        }

        discovered.clear()
        scanning = true
        _connectionState.value = ConnectionState.Scanning

        // Scanning unfiltered and matching in the callback: some trainers only put the FTMS
        // UUID in the scan response, where a hardware service-UUID filter will not see it.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed(scanTimeout, SCAN_DURATION_MS)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        if (hasPermissions()) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
    }

    private val scanTimeout = Runnable {
        stopScan()
        val devices = discovered.values.toList()
        when {
            devices.isEmpty() -> _connectionState.value =
                ConnectionState.Error(appContext.getString(R.string.error_no_trainer))
            devices.size == 1 -> connect(devices.first())
            else -> _connectionState.value = ConnectionState.DevicesFound(devices)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val advertisesFtms = result.scanRecord?.serviceUuids
                ?.any { it.uuid == FTMS_SERVICE } == true
            val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
            val looksLikeWattbike = name != null &&
                WATTBIKE_NAME_HINTS.any { hint -> name.contains(hint, ignoreCase = true) }

            if (!advertisesFtms && !looksLikeWattbike) return

            val trainer = TrainerDevice(name ?: DEFAULT_DEVICE_NAME, device.address)
            discovered[device.address] = trainer

            // Reconnecting to the bike we were last on, or an obvious Wattbike: go straight in.
            if (device.address == preferredAddress || looksLikeWattbike) {
                stopScan()
                connect(trainer)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            _connectionState.value =
                ConnectionState.Error(
                    appContext.getString(R.string.error_scan_failed, errorCode)
                )
        }
    }

    // -------------------------------------------------------------- connecting

    fun connect(device: TrainerDevice) {
        stopScan()
        val remote: BluetoothDevice = runCatching { adapter?.getRemoteDevice(device.address) }
            .getOrNull() ?: run {
            _connectionState.value = ConnectionState.Error(
                appContext.getString(R.string.error_connect_failed, device.name)
            )
            return
        }

        preferredAddress = device.address
        preferredName = device.name
        disconnectRequested = false
        _connectionState.value = ConnectionState.Connecting(device.name)

        closeGatt()
        gatt = remote.connectGatt(
            appContext,
            /* autoConnect = */ false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    /** Drops the connection and stops any reconnect attempts. */
    fun disconnect() {
        disconnectRequested = true
        stopScan()
        handler.removeCallbacks(reconnectRunnable)
        retryConnectRunnable?.let { handler.removeCallbacks(it) }
        retryConnectRunnable = null
        gatt?.let { runCatching { it.disconnect() } }
        closeGatt()
        _liveSample.value = null
        _connectionState.value = ConnectionState.Idle
    }

    /** Releases everything. Call from Activity.onDestroy. */
    fun release() {
        disconnect()
    }

    private fun closeGatt() {
        gatt?.let { runCatching { it.close() } }
        gatt = null
    }

    private val reconnectRunnable = Runnable {
        if (!disconnectRequested) startScan()
    }

    /** A pending retry of a connection that dropped, held so it can be cancelled. */
    private var retryConnectRunnable: Runnable? = null

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS -> {
                    connectAttempt = 0
                    handler.post { runCatching { gatt.discoverServices() } }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGatt()
                    _liveSample.value = null
                    handler.post { handleDisconnect(status) }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error(
                    appContext.getString(R.string.error_discovery_failed, status)
                )
                return
            }
            val service = gatt.getService(FTMS_SERVICE)
            if (service == null) {
                _connectionState.value =
                    ConnectionState.Error(appContext.getString(R.string.error_no_ftms, preferredName))
                runCatching { gatt.disconnect() }
                return
            }
            val characteristic = service.getCharacteristic(INDOOR_BIKE_DATA)
            if (characteristic == null) {
                _connectionState.value =
                    ConnectionState.Error(appContext.getString(R.string.error_no_bike_data, preferredName))
                runCatching { gatt.disconnect() }
                return
            }
            enableNotifications(gatt, characteristic)
            _connectionState.value = ConnectionState.Connected(preferredName)
        }

        // API 33+ delivers the payload as an argument; below that it lives on the characteristic.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == INDOOR_BIKE_DATA) publish(value)
        }

        @Deprecated("Superseded by the three-argument overload on API 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == INDOOR_BIKE_DATA) publish(characteristic.value ?: return)
        }
    }

    private fun handleDisconnect(status: Int) {
        if (disconnectRequested) {
            _connectionState.value = ConnectionState.Idle
            return
        }
        // Status 133 is Android's catch-all GATT error and is usually transient.
        if (status != BluetoothGatt.GATT_SUCCESS && connectAttempt < MAX_CONNECT_ATTEMPTS) {
            connectAttempt++
            val device = TrainerDevice(preferredName, preferredAddress ?: return)
            _connectionState.value = ConnectionState.Reconnecting(preferredName)
            retryConnectRunnable = Runnable { if (!disconnectRequested) connect(device) }
            handler.postDelayed(retryConnectRunnable!!, RECONNECT_DELAY_MS)
            return
        }
        connectAttempt = 0
        _connectionState.value = ConnectionState.Reconnecting(preferredName)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun publish(payload: ByteArray) {
        val parsed = FTMSParser.parseIndoorBikeData(payload) ?: return
        _liveSample.value = LiveSample(parsed, SystemClock.elapsedRealtime())
    }

    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.w(TAG, "setCharacteristicNotification refused for ${characteristic.uuid}")
        }
        val cccd = characteristic.getDescriptor(CCCD) ?: run {
            Log.w(TAG, "Indoor Bike Data has no CCCD descriptor")
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
        private const val TAG = "MyWattBluetooth"

        private const val SCAN_DURATION_MS = 12_000L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MAX_CONNECT_ATTEMPTS = 3
        private const val DEFAULT_DEVICE_NAME = "Trainer"

        /** A sample older than this is treated as stale and the readouts blank out. */
        const val STALE_AFTER_MS = 4_000L

        private val WATTBIKE_NAME_HINTS = listOf("wattbike", "atom")

        private val FTMS_SERVICE: UUID = UUID.fromString(FTMSParser.FTMS_SERVICE_UUID)
        private val INDOOR_BIKE_DATA: UUID = UUID.fromString(FTMSParser.INDOOR_BIKE_DATA_UUID)
        private val CCCD: UUID = UUID.fromString(FTMSParser.CCCD_UUID)

        /** Runtime permissions this app needs on the running Android version. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
