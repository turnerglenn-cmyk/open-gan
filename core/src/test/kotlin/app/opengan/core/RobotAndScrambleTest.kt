package app.opengan.core

import app.opengan.core.cube.Face
import app.opengan.core.cube.Move
import app.opengan.core.robot.GanRobotProtocol
import app.opengan.core.scramble.Scrambler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RobotAndScrambleTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun encodesPairsWithFillerNibble() {
        // R=0, F'=5, D=6 -> 0x05, 0x6F
        val writes = GanRobotProtocol.encode(Move.parseSequence("R F' D"))
        assertEquals(1, writes.size)
        assertArrayEquals(bytes(0x05, 0x6F), writes[0])
    }

    @Test
    fun splitsDoubleTurns() {
        // L2 -> L L -> 0x99 ; B' = 14 -> 0xEF
        assertArrayEquals(bytes(0x99, 0xEF), GanRobotProtocol.encode(Move.parseSequence("L2 B'"))[0])
        assertArrayEquals(bytes(0xAE), GanRobotProtocol.encode(Move.parseSequence("L2 B'"), splitDoubles = false)[0])
    }

    @Test
    fun chunksAtSixteenBytes() {
        val moves = List(40) { Move(Face.R, 1) } // 20 bytes
        val writes = GanRobotProtocol.encode(moves)
        assertEquals(listOf(16, 4), writes.map { it.size })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsU() {
        GanRobotProtocol.encode(Move.parseSequence("R U"))
    }

    @Test
    fun robotSafeScramblesAreValid() {
        val s = Scrambler(Random(42))
        repeat(200) {
            val moves = s.randomMoves(25, robotSafe = true)
            assertEquals(25, moves.size)
            assertTrue(GanRobotProtocol.canExecute(moves))
            for (i in 1 until moves.size) {
                assertTrue(moves[i].face != moves[i - 1].face)
                if (i >= 2 && moves[i].face.axis == moves[i - 1].face.axis) assertTrue(moves[i].face != moves[i - 2].face)
            }
        }
    }
}
