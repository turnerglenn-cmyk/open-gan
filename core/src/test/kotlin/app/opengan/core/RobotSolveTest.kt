package app.opengan.core

import app.opengan.core.cube.CubieCube
import app.opengan.core.cube.Face
import app.opengan.core.cube.Move
import app.opengan.core.robot.GanRobotProtocol
import app.opengan.core.robot.RobotSolve
import app.opengan.core.scramble.Scrambler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RobotSolveTest {

    @Test
    fun uFreeReplacementMatchesU() {
        for (turns in 1..3) {
            val u = Move(Face.U, turns)
            assertEquals(CubieCube.SOLVED.apply(u), CubieCube.SOLVED.apply(RobotSolve.withoutU(u)))
        }
    }

    @Test
    fun solvesAnyHistoryWithoutU() {
        val s = Scrambler(Random(7))
        repeat(100) {
            val history = s.randomMoves(30, robotSafe = false)
            val solution = RobotSolve.solutionFor(history)
            assertTrue(GanRobotProtocol.canExecute(solution))
            assertTrue(CubieCube.SOLVED.apply(history).apply(solution).isSolved)
        }
    }

    @Test
    fun robotScrambleIsUndoneByItsInverse() {
        val scramble = Move.parseSequence("R F' D2 L B R2")
        assertEquals("R2 B' L' D2 F R'", Move.format(RobotSolve.solutionFor(scramble)))
    }

    @Test
    fun simplifyMergesAndCancels() {
        assertEquals("R2 L", Move.format(RobotSolve.simplify(Move.parseSequence("R L R"))))
        assertEquals("", Move.format(RobotSolve.simplify(Move.parseSequence("F R R' F'"))))
        assertEquals("D'", Move.format(RobotSolve.simplify(Move.parseSequence("D2 D"))))
    }
}
