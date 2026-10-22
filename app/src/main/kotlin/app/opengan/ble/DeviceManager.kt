package app.opengan.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.opengan.core.gan.GanDefinitions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    /** MAC from GAN manufacturer data, used to salt the cube's encryption key. */
    val advertisedMac: String?,
)

/** Owns scanning and the (at most one each) connected cube and robot. */
@SuppressLint("MissingPermission")
class DeviceManager(private val context: Context, private val scope: CoroutineScope) {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _cube = MutableStateFlow<GanCubeClient?>(null)
    val cube: StateFlow<GanCubeClient?> = _cube

    private val _robot = MutableStateFlow<GanRobotClient?>(null)
    val robot: StateFlow<GanRobotClient?> = _robot

    private val _connecting = MutableStateFlow<String?>(null)
    val connecting: StateFlow<String?> = _connecting

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var scanStop: Job? = null

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions() = requiredPermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (GanDefinitions.CUBE_NAME_PREFIXES.none { name.startsWith(it) }) return
            val mac = result.scanRecord?.manufacturerSpecificData?.let { data ->
                GanDefinitions.CIC_LIST.firstNotNullOfOrNull { cic -> data.get(cic) }
            }?.let(GanDefinitions::macFromManufacturerData)
            val d = ScannedDevice(name, result.device.address, result.rssi, mac)
            _scanResults.update { list -> (list.filter { it.address != d.address } + d).sortedByDescending { it.rssi } }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _messages.tryEmit("Bluetooth scan failed (code $errorCode)")
        }
    }

    fun startScan(durationMs: Long = 15_000) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !hasPermissions()) {
            _messages.tryEmit("Bluetooth is off or permission is missing")
            return
        }
        if (_scanning.value) return
        _scanResults.value = emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        _scanning.value = true
        scanStop = scope.launch {
            delay(durationMs)
            stopScan()
        }
    }

    fun stopScan() {
        scanStop?.cancel()
        if (_scanning.value) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _scanning.value = false
    }

    /** Connect and work out whether the device is a GAN Robot or a smart cube. */
    fun connect(device: ScannedDevice) {
        val btDevice = adapter?.getRemoteDevice(device.address) ?: return
        stopScan()
        scope.launch {
            _connecting.value = device.name
            try {
                val conn = GattConnection.connect(context, btDevice)
                val protocol = detectCubeProtocol(conn)
                when {
                    protocol != null -> {
                        // Android exposes the real address, unlike Web Bluetooth; prefer the advertised one if present.
                        val cube = GanCubeClient(conn, protocol, device.advertisedMac ?: device.address, scope)
                        cube.start()
                        _cube.value?.disconnect()
                        _cube.value = cube
                        watchDisconnect(conn) { if (_cube.value === cube) _cube.value = null }
                        _messages.tryEmit("Connected ${device.name} (${protocol.name.lowercase()})")
                    }
                    GanRobotClient.isRobot(conn) -> {
                        val robot = GanRobotClient(conn)
                        _robot.value?.disconnect()
                        _robot.value = robot
                        watchDisconnect(conn) { if (_robot.value === robot) _robot.value = null }
                        _messages.tryEmit("Connected GAN Robot")
                    }
                    else -> {
                        conn.disconnect()
                        _messages.tryEmit("${device.name} is not a supported cube or robot")
                    }
                }
            } catch (e: Exception) {
                _messages.tryEmit("Could not connect to ${device.name}: ${e.message}")
            } finally {
                _connecting.value = null
            }
        }
    }

    private fun watchDisconnect(conn: GattConnection, onLost: () -> Unit) {
        scope.launch {
            conn.isConnected.collect { if (!it) onLost() }
        }
    }

    fun report(message: String) {
        _messages.tryEmit(message)
    }

    fun disconnectCube() {
        _cube.value?.disconnect()
        _cube.value = null
    }

    fun disconnectRobot() {
        _robot.value?.disconnect()
        _robot.value = null
    }
}
