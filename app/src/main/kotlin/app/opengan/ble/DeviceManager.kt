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
import android.os.SystemClock
import app.opengan.core.cube.Face
import app.opengan.core.cube.Move
import app.opengan.core.gan.GanDefinitions
import app.opengan.core.robot.GanRobotProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    /** MAC from GAN manufacturer data, used to salt the cube's encryption key. */
    val advertisedMac: String?,
    /** Advertises the GAN Robot's service, so it is probably the robot. */
    val looksLikeRobot: Boolean = false,
)

private enum class Kind { CUBE, ROBOT }

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

    /** Addresses of the last robot and cube connected, tried first by [autoConnect]. */
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    private val _autoStatus = MutableStateFlow<String?>(null)
    /** What one-tap Connect is doing right now, or null when it isn't running. */
    val autoStatus: StateFlow<String?> = _autoStatus
    private var autoJob: Job? = null

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
            val robotService = result.scanRecord?.serviceUuids.orEmpty().any { it.uuid.toString().equals(GanRobotProtocol.SERVICE, ignoreCase = true) }
            val d = ScannedDevice(name, result.device.address, result.rssi, mac, robotService)
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
        stopScan()
        scope.launch { connectNow(device) }
    }

    /** The device's kind once connected, or null if it isn't a supported cube or robot or the connection failed. */
    private suspend fun connectNow(device: ScannedDevice): Kind? {
        val btDevice = adapter?.getRemoteDevice(device.address) ?: return null
        stopScan()
        _connecting.value = device.name
        try {
            val conn = GattConnection.connect(context, btDevice)
            val protocol = detectCubeProtocol(conn)
            return when {
                protocol != null -> {
                    // Android exposes the real address, unlike Web Bluetooth; prefer the advertised one if present.
                    val cube = GanCubeClient(conn, protocol, device.advertisedMac ?: device.address, scope)
                    cube.start()
                    _cube.value?.disconnect()
                    _cube.value = cube
                    watchDisconnect(conn) { if (_cube.value === cube) _cube.value = null }
                    prefs.edit().putString(LAST_CUBE, device.address).apply()
                    _messages.tryEmit("Connected ${device.name} (${protocol.name.lowercase()})")
                    Kind.CUBE
                }
                GanRobotClient.isRobot(conn) -> {
                    val robot = GanRobotClient(conn)
                    _robot.value?.disconnect()
                    _robot.value = robot
                    watchDisconnect(conn) { if (_robot.value === robot) _robot.value = null }
                    prefs.edit().putString(LAST_ROBOT, device.address).apply()
                    _messages.tryEmit("Connected GAN Robot")
                    Kind.ROBOT
                }
                else -> {
                    conn.disconnect()
                    _messages.tryEmit("${device.name} is not a supported cube or robot")
                    null
                }
            }
        } catch (e: Exception) {
            _messages.tryEmit("Could not connect to ${device.name}: ${e.message}")
            return null
        } finally {
            _connecting.value = null
        }
    }

    /**
     * One-tap Connect: find and connect the GAN Robot, then have it make a quick
     * turn and back to wake the smart cube inside it, then find and connect the cube.
     * Either step is skipped when that device is already connected.
     */
    fun autoConnect() {
        if (autoJob?.isActive == true) return
        if (adapter?.bluetoothLeScanner == null || !hasPermissions()) {
            _messages.tryEmit("Bluetooth is off or permission is missing")
            return
        }
        autoJob = scope.launch {
            try {
                if (_robot.value == null) {
                    _autoStatus.value = "Looking for the robot…"
                    findAndConnect(20_000, prefs.getString(LAST_ROBOT, null)) { _robot.value != null }
                }
                repeat(2) {
                    if (_cube.value != null) return@repeat
                    _robot.value?.let { robot ->
                        _autoStatus.value = "Robot is waking the cube…"
                        runCatching { robot.scramble(WAKE_TURNS) }
                    }
                    _autoStatus.value = "Looking for the cube…"
                    findAndConnect(10_000, prefs.getString(LAST_CUBE, null)) { _cube.value != null }
                }
                _messages.tryEmit(
                    when {
                        _robot.value != null && _cube.value != null -> "Robot and cube connected"
                        _robot.value == null && _cube.value == null -> "Couldn't find the robot or a cube. Are they switched on?"
                        _robot.value == null -> "Cube connected, but no robot found. Is it switched on?"
                        else -> "Robot connected, but the cube didn't show up. Turn a face to wake it and tap Connect again."
                    },
                )
            } finally {
                stopScan()
                _autoStatus.value = null
            }
        }
    }

    fun cancelAutoConnect() {
        autoJob?.cancel()
    }

    /**
     * Scan and try GAN devices one at a time until [done] or [timeoutMs] runs out:
     * the remembered device first, then one advertising the robot service, then the strongest signal.
     */
    private suspend fun findAndConnect(timeoutMs: Long, remembered: String?, done: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val tried = mutableSetOf<String>()
        val order = compareByDescending<ScannedDevice> { it.address == remembered }
            .thenByDescending { it.looksLikeRobot }
            .thenByDescending { it.rssi }
        while (!done()) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return
            startScan(remaining)
            val next = withTimeoutOrNull(remaining) {
                scanResults.first { list -> list.any { it.address !in tried } }
                // Give the remembered device or the robot a moment to show up too.
                delay(700)
                _scanResults.value.filter { it.address !in tried }.sortedWith(order).first()
            } ?: return
            tried += next.address
            connectNow(next)
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

    private companion object {
        const val LAST_ROBOT = "last_robot"
        const val LAST_CUBE = "last_cube"

        /** A turn and back: enough movement to wake a sleeping smart cube, without changing it. */
        val WAKE_TURNS = listOf(Move(Face.R, 1), Move(Face.R, 3))
    }
}
