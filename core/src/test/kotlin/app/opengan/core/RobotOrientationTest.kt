package app.opengan.core

import app.opengan.core.cube.CubieCube
import app.opengan.core.cube.Face
import app.opengan.core.cube.Move
import app.opengan.core.robot.GanRobotProtocol
import app.opengan.core.robot.RobotOrientation
import app.opengan.core.robot.RobotSolve
import app.opengan.core.scramble.Scrambler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RobotOrientationTest {

    /** Unit vectors for faces: R=+x, U=+y, F=+z. */
    private val vec = mapOf(
        Face.R to intArrayOf(1, 0, 0), Face.L to intArrayOf(-1, 0, 0),
        Face.U to intArrayOf(0, 1, 0), Face.D to intArrayOf(0, -1, 0),
        Face.F to intArrayOf(0, 0, 1), Face.B to intArrayOf(0, 0, -1),
    )
    private fun faceOf(v: IntArray) = vec.entries.single { it.value.contentEquals(v) }.key

    /** All 24 physical ways to put a cube in the robot (proper rotations). */
    private fun allRotations(): List<Map<Face, Face>> {
        val result = mutableListOf<Map<Face, Face>>()
        val perms = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (p in perms) for (signs in 0 until 8) {
            val s = IntArray(3) { if ((signs shr it) and 1 == 1) -1 else 1 }
            // matrix rows: (row i) has s[i] at column p[i]
            val m = Array(3) { i -> IntArray(3) { j -> if (j == p[i]) s[i] else 0 } }
            val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
            if (det != 1) continue
            result += Face.entries.associateWith { f ->
                val v = vec.getValue(f)
                faceOf(IntArray(3) { i -> (0 until 3).sumOf { j -> m[i][j] * v[j] } })
            }
        }
        return result
    }

    private fun orientations(): List<RobotOrientation> = allRotations().flatMap { rot ->
        listOf(false, true).map { reversed ->
            RobotOrientation.of(GanRobotProtocol.ROBOT_FACES.associateWith { rot.getValue(it) to reversed })
        }
    }

    @Test
    fun thereAre24Placements() {
        assertEquals(24, allRotations().toSet().size)
    }

    @Test
    fun testTurnsRecoverEveryPlacement() {
        for (o in orientations()) {
            val observed = listOf(Face.R, Face.F, Face.D).map { o.toCube(Move(it, 1)) }
            assertEquals(o, RobotOrientation.fromTestTurns(observed[0], observed[1], observed[2]))
        }
    }

    @Test
    fun solvesInEveryPlacement() {
        val s = Scrambler(Random(3))
        for (o in orientations()) {
            repeat(10) {
                // Cube-frame history, e.g. the robot scrambled and the user turned the cube.
                val history = s.randomMoves(25, robotSafe = false)
                val robotMoves = RobotSolve.solutionFor(history, o)
                assertTrue(GanRobotProtocol.canExecute(robotMoves))
                val cube = CubieCube.SOLVED.apply(history).apply(robotMoves.map(o::toCube))
                assertTrue("placement $o", cube.isSolved)
            }
        }
    }

    @Test
    fun avoidingWorksForEveryFace() {
        for (f in Face.entries) for (t in 1..3) {
            val m = Move(f, t)
            val alt = RobotSolve.avoiding(m, f)
            assertTrue(alt.none { it.face == f })
            assertEquals(CubieCube.SOLVED.apply(m), CubieCube.SOLVED.apply(alt))
        }
    }
}

class RobotObservationTest {
    @Test
    fun robotScrambleRevealsOrientation() {
        val s = Scrambler(Random(11))
        val o = RobotOrientation.of(
            mapOf(Face.R to (Face.B to false), Face.L to (Face.F to false), Face.F to (Face.R to false),
                Face.B to (Face.L to false), Face.D to (Face.D to false)),
        )
        repeat(50) {
            val scramble = s.randomMoves(25, robotSafe = true)
            val turns = GanRobotProtocol.executed(scramble)
            val reports = turns.map(o::toCube)
            // Known after the first three scramble moves.
            val firstThree = GanRobotProtocol.executed(scramble.take(3))
            assertEquals(o, RobotOrientation.fromObservations(firstThree, reports.take(firstThree.size)))
            assertEquals(o, RobotOrientation.fromObservations(turns, reports))
        }
    }

    @Test
    fun notEnoughTurnsGivesNull() {
        val turns = Move.parseSequence("R F")
        assertEquals(null, RobotOrientation.fromObservations(turns, turns))
    }

    @Test
    fun missedTurnIsNotMistakenForAnOrientation() {
        val test = RobotOrientation.TEST_TURNS + RobotOrientation.TEST_TURNS.reversed().map { it.inverse }
        // The robot failed to turn F: every later report would pair with the wrong robot face.
        val reports = test.filterIndexed { i, _ -> i != 1 }
        assertEquals(null, RobotOrientation.fromObservations(test, reports))
        assertEquals(RobotOrientation.IDENTITY, RobotOrientation.fromObservations(test, test))
    }

    @Test
    fun correctionReachesTheIntendedState() {
        val s = Scrambler(Random(7))
        repeat(50) {
            val expected = GanRobotProtocol.executed(s.randomMoves(25))
            val missed = Random(it).nextInt(expected.size)
            val reported = expected.filterIndexed { i, _ -> i != missed }
            val fix = RobotOrientation.correction(expected, reported)
            assertEquals(CubieCube.SOLVED.apply(expected), CubieCube.SOLVED.apply(reported + fix))
        }
        assertEquals(emptyList<Move>(), RobotOrientation.correction(Move.parseSequence("R F"), Move.parseSequence("R F")))
    }
}
