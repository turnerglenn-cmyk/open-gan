package app.opengan.core.gan

import app.opengan.core.cube.CubieCube
import app.opengan.core.cube.Face
import app.opengan.core.gan.GanCubeEvent.*

/**
 * Kotlin port of the GAN Gen2/Gen3/Gen4 protocol drivers from
 * gan-web-bluetooth by Andy Fedotov (MIT License),
 * https://github.com/afedotov/gan-web-bluetooth
 *
 * Drivers work on already-decrypted messages and are free of any Android
 * dependencies, so they can be unit-tested on the JVM.
 */
interface CubeLink {
    /** Send a plaintext command; the link encrypts and writes it. Must not block. */
    fun sendCommand(message: ByteArray)
    fun disconnect()
}

interface GanProtocolDriver {
    fun createCommand(command: GanCubeCommand): ByteArray?
    fun handleStateEvent(link: CubeLink, message: ByteArray, now: Long): List<GanCubeEvent>
}

enum class GanProtocol { GEN2, GEN3, GEN4 }

fun driverFor(protocol: GanProtocol): GanProtocolDriver = when (protocol) {
    GanProtocol.GEN2 -> GanGen2Driver()
    GanProtocol.GEN3 -> GanGen3Driver()
    GanProtocol.GEN4 -> GanGen4Driver()
}

private val FACES = Face.entries

/** Reconstruct a full state from 7 corners / 11 edges; the last of each is implied. */
private fun readState(m: BitView, cpAt: Int, coAt: Int, epAt: Int, eoAt: Int): CubieCube {
    val cp = MutableList(7) { m.int(cpAt + it * 3, 3) }
    val co = MutableList(7) { m.int(coAt + it * 2, 2) }
    val ep = MutableList(11) { m.int(epAt + it * 4, 4) }
    val eo = MutableList(11) { m.int(eoAt + it, 1) }
    cp += 28 - cp.sum()
    co += (3 - co.sum() % 3) % 3
    ep += 66 - ep.sum()
    eo += (2 - eo.sum() % 2) % 2
    return CubieCube(cp, co, ep, eo)
}

private fun quaternionComponent(raw: Int): Double = (1 - (raw shr 15) * 2) * (raw and 0x7FFF) / 0x7FFF.toDouble()
private fun velocityComponent(raw: Int): Int = (1 - (raw shr 3) * 2) * (raw and 0x7)

/** Gen2: GAN Mini ui FreePlay, GAN12 ui (FreePlay), GAN356 i Carry (S), GAN356 i3, Monster Go 3Ai, MoYu AI 2023. */
class GanGen2Driver : GanProtocolDriver {
    private var lastSerial = -1
    private var lastMoveTimestamp = 0L
    private var cubeTimestamp = 0L

    override fun createCommand(command: GanCubeCommand): ByteArray {
        val msg = ByteArray(20)
        when (command) {
            GanCubeCommand.REQUEST_FACELETS -> msg[0] = 0x04
            GanCubeCommand.REQUEST_HARDWARE -> msg[0] = 0x05
            GanCubeCommand.REQUEST_BATTERY -> msg[0] = 0x09
            GanCubeCommand.REQUEST_RESET -> intArrayOf(
                0x0A, 0x05, 0x39, 0x77, 0x00, 0x00, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB,
            ).forEachIndexed { i, v -> msg[i] = v.toByte() }
        }
        return msg
    }

    override fun handleStateEvent(link: CubeLink, message: ByteArray, now: Long): List<GanCubeEvent> {
        val m = BitView(message)
        val events = mutableListOf<GanCubeEvent>()
        when (m.int(0, 4)) {
            0x01 -> events += GyroEvent(
                now,
                qx = quaternionComponent(m.int(20, 16)), qy = quaternionComponent(m.int(36, 16)),
                qz = quaternionComponent(m.int(52, 16)), qw = quaternionComponent(m.int(4, 16)),
                vx = velocityComponent(m.int(68, 4)), vy = velocityComponent(m.int(72, 4)),
                vz = velocityComponent(m.int(76, 4)),
            )
            0x02 -> if (lastSerial != -1) { // only after the first facelets event
                val serial = m.int(4, 8)
                val diff = minOf((serial - lastSerial) and 0xFF, 7)
                lastSerial = serial
                if (diff > 0) {
                    for (i in diff - 1 downTo 0) {
                        val face = m.int(12 + 5 * i, 4)
                        val direction = m.int(16 + 5 * i, 1)
                        var elapsed = m.word(47 + 16 * i, 16)
                        if (elapsed == 0L) elapsed = now - lastMoveTimestamp // 16-bit register overflow
                        cubeTimestamp += elapsed
                        if (face in FACES.indices) events += MoveEvent(
                            timestamp = now,
                            serial = (serial - i) and 0xFF,
                            face = FACES[face],
                            direction = direction,
                            localTimestamp = if (i == 0) now else null,
                            cubeTimestamp = cubeTimestamp,
                        )
                    }
                    lastMoveTimestamp = now
                }
            }
            0x04 -> {
                val serial = m.int(4, 8)
                if (lastSerial == -1) lastSerial = serial
                events += FaceletsEvent(now, serial, readState(m, 12, 33, 47, 91))
            }
            0x05 -> {
                val name = String(CharArray(8) { m.int(40 + it * 8, 8).toChar() })
                events += HardwareEvent(
                    now,
                    hardwareName = name.trimEnd('\u0000'),
                    hardwareVersion = "${m.int(8, 8)}.${m.int(16, 8)}",
                    softwareVersion = "${m.int(24, 8)}.${m.int(32, 8)}",
                    gyroSupported = m.int(104, 1) == 1,
                )
            }
            0x09 -> events += BatteryEvent(now, minOf(m.int(8, 8), 100))
            0x0D -> link.disconnect()
        }
        return events
    }
}

/**
 * Shared logic for Gen3 and Gen4: moves go through a FIFO buffer so that
 * gaps in the serial sequence can be filled by requesting move history.
 */
abstract class BufferedMoveDriver : GanProtocolDriver {
    protected var serial = -1
    protected var lastSerial = -1
    protected var lastLocalTimestamp: Long? = null
    private val moveBuffer = ArrayDeque<MoveEvent>()

    protected abstract fun moveHistoryCommand(serial: Int, count: Int): ByteArray

    private fun requestMoveHistory(link: CubeLink, startSerial: Int, requested: Int) {
        // History responses are byte aligned and start at an odd serial: request an odd-aligned, even-sized window.
        var s = startSerial
        var count = requested
        if (s % 2 == 0) s = (s - 1) and 0xFF
        if (count % 2 == 1) count++
        // Never cross the 255 -> 0 edge; firmware spoofs those moves as 'D'.
        count = minOf(count, s + 1)
        runCatching { link.sendCommand(moveHistoryCommand(s, count)) }
    }

    protected fun evictMoveBuffer(link: CubeLink?): List<GanCubeEvent> {
        val evicted = mutableListOf<GanCubeEvent>()
        while (moveBuffer.isNotEmpty()) {
            val head = moveBuffer.first()
            val diff = if (lastSerial == -1) 1 else (head.serial - lastSerial) and 0xFF
            if (diff > 1) {
                if (link != null) requestMoveHistory(link, head.serial, diff)
                break
            }
            evicted += moveBuffer.removeFirst()
            lastSerial = head.serial
        }
        // The buffer is stuck; force a reconnect rather than silently drifting.
        if (link != null && moveBuffer.size > 16) link.disconnect()
        return evicted
    }

    private fun isSerialInRange(start: Int, end: Int, s: Int, closedStart: Boolean = false, closedEnd: Boolean = false) =
        ((end - start) and 0xFF) >= ((s - start) and 0xFF) &&
            (closedStart || ((start - s) and 0xFF) > 0) &&
            (closedEnd || ((end - s) and 0xFF) > 0)

    protected fun injectMissedMove(move: MoveEvent) {
        if (moveBuffer.isNotEmpty()) {
            val head = moveBuffer.first()
            if (moveBuffer.any { it.serial == move.serial }) return
            if (!isSerialInRange(lastSerial, head.serial, move.serial)) return
            // History arrives newest-first, so only the move just before the head fits.
            if (move.serial == ((head.serial - 1) and 0xFF)) moveBuffer.addFirst(move)
        } else if (isSerialInRange(lastSerial, serial, move.serial, closedEnd = true)) {
            // A lost move recovered via the periodic facelets event.
            moveBuffer.addFirst(move)
        }
    }

    protected fun pushMove(move: MoveEvent) = moveBuffer.addLast(move)

    protected fun checkIfMoveMissed(link: CubeLink) {
        val diff = (serial - lastSerial) and 0xFF
        if (diff > 0 && serial != 0) { // serial 0 triggers a firmware bug
            val start = moveBuffer.firstOrNull()?.serial ?: ((serial + 1) and 0xFF)
            requestMoveHistory(link, start, diff + 1)
        }
    }

    protected fun onFaceletsSerial(link: CubeLink, newSerial: Int, now: Long) {
        serial = newSerial
        if (lastSerial != -1) {
            val last = lastLocalTimestamp
            // Debounce while the cube is actively being turned.
            if (last != null && now - last > 500) checkIfMoveMissed(link)
        }
        if (lastSerial == -1) lastSerial = newSerial
    }

    /** Move history face encoding differs from live move encoding. */
    protected fun historyFace(raw: Int): Face? = intArrayOf(1, 5, 3, 0, 4, 2).indexOf(raw).let { FACES.getOrNull(it) }

    protected fun liveFace(raw: Int): Face? = intArrayOf(2, 32, 8, 1, 16, 4).indexOf(raw).let { FACES.getOrNull(it) }
}

/** Gen3: GAN356 i Carry 2. */
class GanGen3Driver : BufferedMoveDriver() {

    override fun createCommand(command: GanCubeCommand): ByteArray {
        val msg = ByteArray(16)
        val bytes = when (command) {
            GanCubeCommand.REQUEST_FACELETS -> intArrayOf(0x68, 0x01)
            GanCubeCommand.REQUEST_HARDWARE -> intArrayOf(0x68, 0x04)
            GanCubeCommand.REQUEST_BATTERY -> intArrayOf(0x68, 0x07)
            GanCubeCommand.REQUEST_RESET -> intArrayOf(
                0x68, 0x05, 0x05, 0x39, 0x77, 0x00, 0x00, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB,
            )
        }
        bytes.forEachIndexed { i, v -> msg[i] = v.toByte() }
        return msg
    }

    override fun moveHistoryCommand(serial: Int, count: Int) = ByteArray(16).also {
        it[0] = 0x68; it[1] = 0x03; it[2] = serial.toByte(); it[4] = count.toByte()
    }

    override fun handleStateEvent(link: CubeLink, message: ByteArray, now: Long): List<GanCubeEvent> {
        val m = BitView(message)
        val magic = m.int(0, 8)
        val type = m.int(8, 8)
        val len = m.int(16, 8)
        if (magic != 0x55 || len <= 0) return emptyList()
        val events = mutableListOf<GanCubeEvent>()
        when (type) {
            0x01 -> if (lastSerial != -1) {
                lastLocalTimestamp = now
                val cubeTs = m.word(24, 32, true)
                serial = m.int(56, 16, true)
                val face = liveFace(m.int(74, 6))
                if (face != null) pushMove(MoveEvent(now, serial, face, m.int(72, 2), now, cubeTs))
                events += evictMoveBuffer(link)
            }
            0x06 -> {
                val start = m.int(24, 8)
                val count = (len - 1) * 2
                for (i in 0 until count) {
                    val face = historyFace(m.int(32 + 4 * i, 3)) ?: continue
                    injectMissedMove(
                        MoveEvent(now, (start - i) and 0xFF, face, m.int(35 + 4 * i, 1), null, null),
                    )
                }
                events += evictMoveBuffer(null)
            }
            0x02 -> {
                val s = m.int(24, 16, true)
                onFaceletsSerial(link, s, now)
                events += FaceletsEvent(now, s, readState(m, 40, 61, 77, 121))
            }
            0x07 -> events += HardwareEvent(
                now,
                hardwareName = String(CharArray(5) { m.int(32 + it * 8, 8).toChar() }),
                softwareVersion = "${m.int(72, 4)}.${m.int(76, 4)}",
                hardwareVersion = "${m.int(80, 4)}.${m.int(84, 4)}",
            )
            0x10 -> events += BatteryEvent(now, minOf(m.int(24, 8), 100))
            0x11 -> link.disconnect()
        }
        return events
    }
}

/** Gen4: GAN12 ui Maglev, GAN14 ui FreePlay. */
class GanGen4Driver : BufferedMoveDriver() {
    private val hwInfo = mutableMapOf<Int, String>()

    override fun createCommand(command: GanCubeCommand): ByteArray {
        val msg = ByteArray(20)
        val bytes = when (command) {
            GanCubeCommand.REQUEST_FACELETS -> intArrayOf(0xDD, 0x04, 0x00, 0xED, 0x00, 0x00)
            GanCubeCommand.REQUEST_HARDWARE -> {
                hwInfo.clear()
                intArrayOf(0xDF, 0x03, 0x00, 0x00, 0x00)
            }
            GanCubeCommand.REQUEST_BATTERY -> intArrayOf(0xDD, 0x04, 0x00, 0xEF, 0x00, 0x00)
            GanCubeCommand.REQUEST_RESET -> intArrayOf(
                0xD2, 0x0D, 0x05, 0x39, 0x77, 0x00, 0x00, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB,
            )
        }
        bytes.forEachIndexed { i, v -> msg[i] = v.toByte() }
        return msg
    }

    override fun moveHistoryCommand(serial: Int, count: Int) = ByteArray(20).also {
        it[0] = 0xD1.toByte(); it[1] = 0x04; it[2] = serial.toByte(); it[4] = count.toByte()
    }

    override fun handleStateEvent(link: CubeLink, message: ByteArray, now: Long): List<GanCubeEvent> {
        val m = BitView(message)
        val type = m.int(0, 8)
        val len = m.int(8, 8)
        val events = mutableListOf<GanCubeEvent>()
        when {
            type == 0x01 -> if (lastSerial != -1) {
                lastLocalTimestamp = now
                val cubeTs = m.word(16, 32, true)
                serial = m.int(48, 16, true)
                val face = liveFace(m.int(66, 6))
                if (face != null) pushMove(MoveEvent(now, serial, face, m.int(64, 2), now, cubeTs))
                events += evictMoveBuffer(link)
            }
            type == 0xD1 -> {
                val start = m.int(16, 8)
                val count = (len - 1) * 2
                for (i in 0 until count) {
                    val face = historyFace(m.int(24 + 4 * i, 3)) ?: continue
                    injectMissedMove(
                        MoveEvent(now, (start - i) and 0xFF, face, m.int(27 + 4 * i, 1), null, null),
                    )
                }
                events += evictMoveBuffer(null)
            }
            type == 0xED -> {
                val s = m.int(16, 16, true)
                onFaceletsSerial(link, s, now)
                events += FaceletsEvent(now, s, readState(m, 32, 53, 69, 113))
            }
            type in 0xFA..0xFE -> {
                when (type) {
                    0xFA -> hwInfo[type] = "%04d-%02d-%02d".format(m.int(24, 16, true), m.int(40, 8), m.int(48, 8))
                    0xFC -> hwInfo[type] = String(CharArray(maxOf(len - 1, 0)) { m.int(24 + it * 8, 8).toChar() })
                    0xFD -> hwInfo[type] = "${m.int(24, 4)}.${m.int(28, 4)}"
                    0xFE -> hwInfo[type] = "${m.int(24, 4)}.${m.int(28, 4)}"
                }
                if (hwInfo.size == 4) {
                    events += HardwareEvent(
                        now,
                        hardwareName = hwInfo[0xFC],
                        hardwareVersion = hwInfo[0xFE],
                        softwareVersion = hwInfo[0xFD],
                        productDate = hwInfo[0xFA],
                        gyroSupported = hwInfo[0xFC] == "GAN12uiM",
                    )
                }
            }
            type == 0xEC -> events += GyroEvent(
                now,
                qx = quaternionComponent(m.int(32, 16)), qy = quaternionComponent(m.int(48, 16)),
                qz = quaternionComponent(m.int(64, 16)), qw = quaternionComponent(m.int(16, 16)),
                vx = velocityComponent(m.int(80, 4)), vy = velocityComponent(m.int(84, 4)),
                vz = velocityComponent(m.int(88, 4)),
            )
            type == 0xEF -> events += BatteryEvent(now, minOf(m.int(8 + len * 8, 8), 100))
            type == 0xEA -> link.disconnect()
        }
        return events
    }
}
