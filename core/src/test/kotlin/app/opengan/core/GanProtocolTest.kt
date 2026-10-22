package app.opengan.core

import app.opengan.core.cube.CubieCube
import app.opengan.core.cube.Face
import app.opengan.core.gan.CubeLink
import app.opengan.core.gan.GanCubeEvent
import app.opengan.core.gan.GanDefinitions
import app.opengan.core.gan.GanEncrypter
import app.opengan.core.gan.GanGen2Driver
import app.opengan.core.gan.GanGen3Driver
import app.opengan.core.gan.GanGen4Driver
import app.opengan.core.timer.SmartTimer
import app.opengan.core.timer.TimedMove
import app.opengan.core.timer.TimestampFit
import app.opengan.core.cube.Move
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds MSB-first bit messages for driver tests. */
private class BitWriter(sizeBytes: Int) {
    val bytes = ByteArray(sizeBytes)
    private var pos = 0
    fun put(value: Long, bits: Int) = apply {
        for (i in bits - 1 downTo 0) {
            if ((value shr i) and 1L == 1L) bytes[pos / 8] = (bytes[pos / 8].toInt() or (0x80 ushr (pos % 8))).toByte()
            pos++
        }
    }
    fun put(value: Int, bits: Int) = put(value.toLong(), bits)
    fun le16(v: Int) = put(v and 0xFF, 8).put((v shr 8) and 0xFF, 8)
    fun le32(v: Long) = apply { for (i in 0 until 4) put((v shr (8 * i)) and 0xFF, 8) }
    /** [gap] = padding bits between corner orientation and edge permutation (2 on Gen3/Gen4). */
    fun state(c: CubieCube, gap: Int = 0) = apply {
        for (i in 0 until 7) put(c.cp[i], 3)
        for (i in 0 until 7) put(c.co[i], 2)
        put(0, gap)
        for (i in 0 until 11) put(c.ep[i], 4)
        for (i in 0 until 11) put(c.eo[i], 1)
    }
}

private class FakeLink : CubeLink {
    val sent = mutableListOf<ByteArray>()
    var disconnected = false
    override fun sendCommand(message: ByteArray) { sent += message }
    override fun disconnect() { disconnected = true }
}

class GanProtocolTest {

    private val scrambled = CubieCube.SOLVED.apply("F R U' D2 B L'")

    @Test
    fun encryptionRoundTrip() {
        val enc = GanEncrypter(GanDefinitions.KEY_GAN, GanDefinitions.saltFromMac("AB:12:CD:34:EF:56"))
        val plain = ByteArray(20) { (it * 7).toByte() }
        val cipher = enc.encrypt(plain)
        assertTrue(!cipher.contentEquals(plain))
        assertArrayEquals(plain, enc.decrypt(cipher))
    }

    @Test
    fun macFromManufacturerData() {
        // Bytes 3..8 hold the MAC reversed.
        val data = byteArrayOf(0, 0, 0, 0x56, 0xEF.toByte(), 0x34, 0xCD.toByte(), 0x12, 0xAB.toByte(), 0x77)
        assertEquals("AB:12:CD:34:EF:56", GanDefinitions.macFromManufacturerData(data))
    }

    @Test
    fun gen2FaceletsAndMoves() {
        val d = GanGen2Driver()
        val link = FakeLink()
        val facelets = BitWriter(20).put(0x04, 4).put(10, 8).state(scrambled).bytes
        val ev = d.handleStateEvent(link, facelets, 1000).single() as GanCubeEvent.FaceletsEvent
        assertEquals(scrambled, ev.state)

        // Two new moves since serial 10: newest (i=0) R', previous (i=1) U.
        val w = BitWriter(20).put(0x02, 4).put(12, 8)
        w.put(1, 4).put(1, 1) // i=0: R'
        w.put(0, 4).put(0, 1) // i=1: U
        repeat(5) { w.put(0, 5) } // remaining move slots up to bit 47
        w.put(120, 16).put(80, 16) // elapsed for i=0, i=1
        val moves = d.handleStateEvent(link, w.bytes, 2000).map { it as GanCubeEvent.MoveEvent }
        assertEquals(listOf("U", "R'"), moves.map { it.move.toString() })
        assertEquals(listOf(11, 12), moves.map { it.serial })
        assertEquals(listOf(80L, 200L), moves.map { it.cubeTimestamp })
        assertEquals(listOf(null, 2000L), moves.map { it.localTimestamp })
    }

    private fun gen4Move(serial: Int, faceCode: Int, dir: Int, cubeTs: Long) =
        BitWriter(20).put(0x01, 8).put(9, 8).le32(cubeTs).le16(serial).put(dir, 2).put(faceCode, 6).bytes

    @Test
    fun gen4RecoversMissedMoveFromHistory() {
        val d = GanGen4Driver()
        val link = FakeLink()
        val f = d.handleStateEvent(link, BitWriter(20).put(0xED, 8).put(18, 8).le16(5).state(scrambled, gap = 2).bytes, 0)
        assertEquals(scrambled, (f.single() as GanCubeEvent.FaceletsEvent).state)

        // Serial 6 arrives (R = code 32), then 8 (F = code 8): 7 was lost.
        assertEquals(1, d.handleStateEvent(link, gen4Move(6, 32, 0, 100), 100).size)
        assertTrue(d.handleStateEvent(link, gen4Move(8, 8, 0, 300), 300).isEmpty())
        val req = link.sent.single()
        assertEquals(0xD1, req[0].toInt() and 0xFF)

        // History response, newest first. History face codes: U=1, R=5, F=3, D=0, L=4, B=2.
        val hist = BitWriter(20).put(0xD1, 8).put(2, 8).put(8, 8)
            .put(3, 3).put(0, 1) // serial 8: F
            .put(0, 3).put(1, 1) // serial 7: D'
            .bytes
        val recovered = d.handleStateEvent(link, hist, 400).map { it as GanCubeEvent.MoveEvent }
        assertEquals(listOf(7, 8), recovered.map { it.serial })
        assertEquals(listOf("D'", "F"), recovered.map { it.move.toString() })
    }

    @Test
    fun gen3BatteryAndMove() {
        val d = GanGen3Driver()
        val link = FakeLink()
        val battery = BitWriter(16).put(0x55, 8).put(0x10, 8).put(1, 8).put(87, 8).bytes
        assertEquals(87, (d.handleStateEvent(link, battery, 0).single() as GanCubeEvent.BatteryEvent).batteryLevel)

        val f = d.handleStateEvent(link, BitWriter(20).put(0x55, 8).put(0x02, 8).put(10, 8).le16(1).state(scrambled, gap = 2).bytes, 0)
        assertEquals(scrambled, (f.single() as GanCubeEvent.FaceletsEvent).state)
        val mv = BitWriter(16).put(0x55, 8).put(0x01, 8).put(7, 8).le32(5000).le16(2).put(1, 2).put(4, 6).bytes
        val e = d.handleStateEvent(link, mv, 10).single() as GanCubeEvent.MoveEvent
        assertEquals(Face.B, e.face)
        assertEquals("B'", e.move.toString())
        assertEquals(5000L, e.cubeTimestamp)
    }

    @Test
    fun timestampFitUsesCubeClock() {
        val moves = listOf(
            TimedMove("R", 1000, 0), TimedMove("U", 1290, 250), TimedMove("R'", null, null), TimedMove("U'", 1540, 520),
        )
        val fitted = TimestampFit.fit(moves)
        assertEquals(0L, fitted.first().cubeTimestamp)
        assertTrue(fitted.zipWithNext().all { (a, b) -> b.cubeTimestamp!! >= a.cubeTimestamp!! })
        val time = TimestampFit.solveTime(moves)!!
        assertTrue("solve time $time", time in 500L..560L)
    }

    @Test
    fun smartTimerFullFlow() {
        val phases = mutableListOf<SmartTimer.Phase>()
        val t = SmartTimer { phases += it }
        val scramble = Move.parseSequence("R F")
        t.setScramble(scramble)
        assertTrue(t.phase is SmartTimer.Phase.Scrambling)
        t.onMove(Move.parse("R"), 0, 0)
        assertEquals(1, (t.phase as SmartTimer.Phase.Scrambling).applied)
        t.onMove(Move.parse("F"), 10, 10)
        assertTrue(t.phase is SmartTimer.Phase.Ready)
        t.onMove(Move.parse("F'"), 1000, 1000)
        assertTrue(t.phase is SmartTimer.Phase.Running)
        t.onMove(Move.parse("R'"), 2500, 2500)
        val result = (t.phase as SmartTimer.Phase.Finished).result
        assertEquals(1500L, result.timeMs)
        assertEquals("F' R'", result.solution)
        assertEquals("R F", result.scramble)
    }
}
