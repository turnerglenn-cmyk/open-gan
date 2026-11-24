package app.opengan.core

import app.opengan.core.cube.CubieCube
import app.opengan.core.cube.Move
import app.opengan.core.timer.SmartTimer
import app.opengan.core.timer.SmartTimer.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the timer does when a cube drops its connection and comes back. */
class SmartTimerResyncTest {

    private val scramble = Move.parseSequence("R U F")

    private fun readyTimer(): SmartTimer {
        val t = SmartTimer()
        t.setScramble(scramble)
        scramble.forEachIndexed { i, m -> t.onMove(m, 1000L + i, null) }
        assertTrue(t.phase is Phase.Ready)
        return t
    }

    @Test
    fun solveContinuesAfterReconnect() {
        val t = readyTimer()
        // Start: undo F, then the connection drops while U' is turned.
        t.onMove(Move.parse("F'"), 10_000, 0)
        // Back again: the cube says only R is left. The new connection's cube clock restarted.
        t.resync(CubieCube.SOLVED.apply("R"), 12_000)
        assertTrue(t.phase is Phase.Running)
        t.onMove(Move.parse("R'"), 13_500, 40)
        val p = t.phase as Phase.Finished
        assertEquals(3_500L, p.result.timeMs)
        assertFalse(p.result.approximate)
    }

    @Test
    fun solvedWhileDisconnectedStopsAtReconnect() {
        val t = readyTimer()
        t.onMove(Move.parse("F'"), 10_000, 0)
        t.resync(CubieCube.SOLVED, 14_000)
        val p = t.phase as Phase.Finished
        assertEquals(4_000L, p.result.timeMs)
        assertTrue(p.result.approximate)
    }

    @Test
    fun scrambleTrackingPicksUpTheReportedState() {
        val t = SmartTimer()
        t.setScramble(scramble)
        t.onMove(Move.parse("R"), 1, null)
        t.resync(CubieCube.SOLVED.apply("R U"), 2)
        assertEquals(2, (t.phase as Phase.Scrambling).applied)
        t.resync(CubieCube.SOLVED.apply(scramble), 3)
        assertTrue(t.phase is Phase.Ready)
    }

    @Test
    fun readyCubeTurnedWhileAwayGoesBackToScrambling() {
        val t = readyTimer()
        t.resync(CubieCube.SOLVED.apply("R U"), 5_000)
        assertEquals(2, (t.phase as Phase.Scrambling).applied)
    }

    @Test
    fun unchangedReadyCubeStaysReady() {
        val t = readyTimer()
        t.resync(CubieCube.SOLVED.apply(scramble), 5_000)
        assertTrue(t.phase is Phase.Ready)
    }
}
