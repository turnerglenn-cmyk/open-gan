package app.opengan.core

import app.opengan.core.cube.CubieCube
import app.opengan.core.cube.Face
import app.opengan.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeTest {

    @Test
    fun solvedFacelets() {
        assertEquals(CubieCube.SOLVED_FACELETS, CubieCube.SOLVED.toFacelets())
    }

    /** Reference vector from gan-web-bluetooth's toKociembaFacelets docs. */
    @Test
    fun fThenR() {
        val c = CubieCube.SOLVED.apply("F R")
        assertEquals(listOf(0, 5, 2, 1, 7, 4, 6, 3), c.cp)
        assertEquals(listOf(1, 2, 0, 2, 1, 1, 0, 2), c.co)
        assertEquals(listOf(1, 9, 2, 3, 11, 8, 6, 7, 4, 5, 10, 0), c.ep)
        assertEquals(listOf(1, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0), c.eo)
        assertEquals("UUFUUFLLFUUURRRRRRFFRFFDFFDRRBDDBDDBLLDLLDLLDLBBUBBUBB", c.toFacelets())
    }

    @Test
    fun everyFaceHasOrderFour() {
        for (f in Face.entries) {
            val m = Move(f, 1)
            assertFalse(CubieCube.SOLVED.apply(m).isSolved)
            assertTrue(CubieCube.SOLVED.apply(listOf(m, m, m, m)).isSolved)
        }
    }

    @Test
    fun sexyMoveHasOrderSix() {
        var c = CubieCube.SOLVED
        repeat(6) { c = c.apply("R U R' U'") }
        assertTrue(c.isSolved)
    }

    @Test
    fun scrambleThenInverseIsSolved() {
        val moves = Move.parseSequence("D2 F' L B2 R D' F2 L' U B R2 D")
        val inverse = moves.reversed().map { it.inverse }
        assertTrue(CubieCube.SOLVED.apply(moves).apply(inverse).isSolved)
    }

    @Test
    fun parseAndFormat() {
        assertEquals("R U' F2 D", Move.format(Move.parseSequence("  R U'  F2 D ")))
        assertEquals(Move(Face.B, 2), Move.parse("B2'"))
    }
}
