package app.opengan.ble

import android.os.SystemClock
import app.opengan.core.cube.Move
import app.opengan.core.gan.CubeLink
import app.opengan.core.gan.GanCubeCommand
import app.opengan.core.gan.GanCubeEvent
import app.opengan.core.gan.GanDefinitions
import app.opengan.core.gan.GanEncrypter
import app.opengan.core.gan.GanProtocol
import app.opengan.core.gan.driverFor
import app.opengan.core.robot.GanRobotProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** A connected GAN smart cube: decrypts notifications and turns them into [GanCubeEvent]s. */
class GanCubeClient(
    private val conn: GattConnection,
    val protocol: GanProtocol,
    mac: String,
    private val scope: CoroutineScope,
) : CubeLink {

    private val ids = when (protocol) {
        GanProtocol.GEN2 -> Triple(GanDefinitions.GEN2_SERVICE, GanDefinitions.GEN2_COMMAND, GanDefinitions.GEN2_STATE)
        GanProtocol.GEN3 -> Triple(GanDefinitions.GEN3_SERVICE, GanDefinitions.GEN3_COMMAND, GanDefinitions.GEN3_STATE)
        GanProtocol.GEN4 -> Triple(GanDefinitions.GEN4_SERVICE, GanDefinitions.GEN4_COMMAND, GanDefinitions.GEN4_STATE)
    }
    private val commandChar = conn.characteristic(ids.first, ids.second)
    private val stateChar = conn.characteristic(ids.first, ids.third)

    private val key = if (conn.device.name.orEmpty().startsWith("AiCube")) GanDefinitions.KEY_MOYU_AI else GanDefinitions.KEY_GAN
    private val encrypter = GanEncrypter(key, GanDefinitions.saltFromMac(mac))
    private val driver = driverFor(protocol)

    private val _events = MutableSharedFlow<GanCubeEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GanCubeEvent> = _events

    val name: String get() = conn.device.name ?: "GAN cube"
    val isConnected get() = conn.isConnected

    suspend fun start() {
        scope.launch {
            conn.notifications.collect { n ->
                if (n.characteristic != stateChar.uuid || n.value.size < 16) return@collect
                val plain = encrypter.decrypt(n.value)
                driver.handleStateEvent(this@GanCubeClient, plain, SystemClock.elapsedRealtime()).forEach { _events.emit(it) }
            }
        }
        scope.launch {
            conn.isConnected.collect { if (!it) _events.emit(GanCubeEvent.DisconnectEvent(SystemClock.elapsedRealtime())) }
        }
        conn.enableNotifications(stateChar)
        send(GanCubeCommand.REQUEST_HARDWARE)
        send(GanCubeCommand.REQUEST_FACELETS)
        send(GanCubeCommand.REQUEST_BATTERY)
    }

    suspend fun send(command: GanCubeCommand) {
        driver.createCommand(command)?.let { conn.write(commandChar, encrypter.encrypt(it)) }
    }

    /** Tell the cube its current physical state is solved. */
    suspend fun markSolved() {
        send(GanCubeCommand.REQUEST_RESET)
        send(GanCubeCommand.REQUEST_FACELETS)
    }

    override fun sendCommand(message: ByteArray) {
        scope.launch { runCatching { conn.write(commandChar, encrypter.encrypt(message)) } }
    }

    override fun disconnect() = conn.disconnect()
}

/** A connected GAN Robot. */
class GanRobotClient(private val conn: GattConnection) {
    private val scrambleChar = conn.characteristic(GanRobotProtocol.SERVICE, GanRobotProtocol.SCRAMBLE_CHARACTERISTIC)
    private val statusChar = conn.characteristic(GanRobotProtocol.SERVICE, GanRobotProtocol.STATUS_CHARACTERISTIC)

    val name: String get() = conn.device.name ?: "GAN Robot"
    val isConnected get() = conn.isConnected

    /**
     * Send a scramble and suspend until the robot has finished turning.
     * [onProgress] receives (chunks done, total chunks).
     */
    suspend fun scramble(moves: List<Move>, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val chunks = GanRobotProtocol.encode(moves)
        chunks.forEachIndexed { i, chunk ->
            onProgress(i, chunks.size)
            conn.write(scrambleChar, chunk)
            awaitIdle()
        }
        onProgress(chunks.size, chunks.size)
    }

    /** Status reads non-zero while turning and 0 when idle. */
    private suspend fun awaitIdle() {
        var sawBusy = false
        val startedAt = SystemClock.elapsedRealtime()
        withTimeout(120_000) {
            while (true) {
                val busy = (conn.read(statusChar).firstOrNull()?.toInt() ?: 0) != 0
                if (busy) sawBusy = true
                if (sawBusy && !busy) return@withTimeout
                // If the robot never reports busy (e.g. a no-op chunk), don't wait forever.
                if (!sawBusy && SystemClock.elapsedRealtime() - startedAt > 3000) return@withTimeout
                delay(20)
            }
        }
    }

    fun disconnect() = conn.disconnect()

    companion object {
        suspend fun isRobot(conn: GattConnection): Boolean {
            if (conn.service(GanRobotProtocol.SERVICE) == null) return false
            val info = runCatching {
                conn.characteristic(GanRobotProtocol.DEVICE_INFO_SERVICE, GanRobotProtocol.MODEL_NUMBER_CHARACTERISTIC)
            }.getOrNull() ?: return false
            return GanRobotProtocol.isRobotModel(String(conn.read(info), Charsets.UTF_8))
        }
    }
}

fun detectCubeProtocol(conn: GattConnection): GanProtocol? = when {
    conn.service(GanDefinitions.GEN2_SERVICE) != null -> GanProtocol.GEN2
    conn.service(GanDefinitions.GEN3_SERVICE) != null -> GanProtocol.GEN3
    conn.service(GanDefinitions.GEN4_SERVICE) != null -> GanProtocol.GEN4
    else -> null
}
