package app.opengan.core.timer

import app.opengan.core.cube.CubieCube
import app.opengan.core.cube.Move

/**
 * Smart cube solve timer.
 *
 * Flow: a scramble is set -> the user (or the GAN Robot) applies it while the
 * timer tracks progress -> once the cube matches the scramble state the timer
 * is armed -> the first turn starts it -> reaching the solved state stops it.
 *
 * Pure state machine with no Android or threading dependencies; feed it cube
 * events from a single thread.
 */
class SmartTimer(private val listener: (Phase) -> Unit = {}) {

    sealed interface Phase {
        /** No scramble; the timer only tracks the cube. */
        data object Idle : Phase

        /** Waiting for the cube to reach the scramble state. [applied] = scramble moves matched so far. */
        data class Scrambling(val scramble: List<Move>, val applied: Int) : Phase

        /** Cube is scrambled; the first turn starts the timer. */
        data class Ready(val scramble: List<Move>?) : Phase

        data class Running(val scramble: List<Move>?, val startedAt: Long, val moveCount: Int) : Phase

        data class Finished(val result: SolveResult) : Phase
    }

    var cube: CubieCube = CubieCube.SOLVED
        private set

    var phase: Phase = Phase.Idle
        private set(value) {
            field = value
            listener(value)
        }

    private var scrambleStates: List<CubieCube> = emptyList()
    private val solveMoves = mutableListOf<TimedMove>()
    private var startedAt = 0L

    /**
     * The cube reconnected during this solve. Cube timestamps may restart on a new
     * connection, so the time comes from local timestamps instead of the cube's clock.
     */
    private var spliced = false

    /** Set a scramble to apply. Progress is measured against states reached from solved. */
    fun setScramble(scramble: List<Move>) {
        scrambleStates = scramble.runningFold(CubieCube.SOLVED) { c, m -> c.apply(m) }
        phase = Phase.Scrambling(scramble, matchedPrefix())
        checkScrambled()
    }

    /** Arm without a scramble: start on the next turn, stop when solved. */
    fun armFree() {
        scrambleStates = emptyList()
        phase = Phase.Ready(null)
    }

    fun reset() {
        scrambleStates = emptyList()
        solveMoves.clear()
        phase = Phase.Idle
    }

    /** The cube reported its full state (on connect and periodically). */
    fun onFacelets(state: CubieCube) {
        if (state == cube) return
        cube = state
        if (phase is Phase.Scrambling) checkScrambled()
    }

    /**
     * The cube reconnected after an unexpected disconnect and reported its full state.
     * Turns made while it was away are lost, so take the cube's word for its state:
     * a solve in progress carries on (and stops now if the cube is already solved),
     * scramble tracking picks up where the cube is, and a Ready timer whose cube was
     * turned meanwhile goes back to scrambling. [now] is the local time of the report.
     */
    fun resync(state: CubieCube, now: Long) {
        val changed = state != cube
        cube = state
        when (val p = phase) {
            is Phase.Running -> {
                spliced = true
                if (cube.isSolved) finish(p.scramble, endedAt = now)
            }
            is Phase.Scrambling -> checkScrambled()
            is Phase.Ready -> if (changed && p.scramble != null && scrambleStates.isNotEmpty()) {
                phase = Phase.Scrambling(p.scramble, matchedPrefix())
                checkScrambled()
            }
            Phase.Idle, is Phase.Finished -> Unit
        }
    }

    fun onMove(move: Move, localTimestamp: Long?, cubeTimestamp: Long?) {
        cube = cube.apply(move)
        when (val p = phase) {
            is Phase.Scrambling -> checkScrambled()
            is Phase.Ready -> {
                solveMoves.clear()
                solveMoves += TimedMove(move.toString(), localTimestamp, cubeTimestamp)
                startedAt = localTimestamp ?: 0L
                spliced = false
                phase = Phase.Running(p.scramble, startedAt, 1)
                if (cube.isSolved) finish(p.scramble) // e.g. undoing a one-move scramble
            }
            is Phase.Running -> {
                solveMoves += TimedMove(move.toString(), localTimestamp, cubeTimestamp)
                if (cube.isSolved) finish(p.scramble) else phase = p.copy(moveCount = solveMoves.size)
            }
            Phase.Idle, is Phase.Finished -> Unit
        }
    }

    private fun matchedPrefix(): Int = scrambleStates.indexOfLast { it == cube }.coerceAtLeast(0)

    private fun checkScrambled() {
        val p = phase as? Phase.Scrambling ?: return
        if (cube == scrambleStates.last()) {
            phase = Phase.Ready(p.scramble)
        } else {
            val applied = scrambleStates.indexOfLast { it == cube }
            if (applied >= 0 && applied != p.applied) phase = p.copy(applied = applied)
        }
    }

    /** [endedAt]: the solve ended while the cube was disconnected; it was seen solved at this local time. */
    private fun finish(scramble: List<Move>?, endedAt: Long? = null) {
        if (spliced || endedAt != null) {
            val end = endedAt ?: solveMoves.mapNotNull { it.localTimestamp }.lastOrNull() ?: startedAt
            phase = Phase.Finished(
                SolveResult(
                    timeMs = (end - startedAt).coerceAtLeast(0),
                    scramble = scramble?.let(Move::format).orEmpty(),
                    solution = solveMoves.joinToString(" ") { it.move },
                    approximate = endedAt != null,
                ),
            )
            spliced = false
            return
        }
        val fitted = TimestampFit.fit(solveMoves)
        val time = fitted.last().cubeTimestamp!! - fitted.first().cubeTimestamp!!
        phase = Phase.Finished(
            SolveResult(
                timeMs = time,
                scramble = scramble?.let(Move::format).orEmpty(),
                solution = fitted.joinToString(" ") { it.move },
                moveTimestamps = fitted.map { it.cubeTimestamp!! },
            ),
        )
    }
}

data class SolveResult(
    val timeMs: Long,
    val scramble: String,
    val solution: String,
    /** Fitted per-move times in ms from the first move, for reconstructions. */
    val moveTimestamps: List<Long> = emptyList(),
    /**
     * The cube was disconnected when the solve ended, so the time runs to when it
     * reconnected and was seen solved, and the solution misses the last turns.
     */
    val approximate: Boolean = false,
) {
    val moveCount: Int get() = if (solution.isBlank()) 0 else solution.split(' ').size

    /** Turns per second. The first move starts the clock, so it is not counted. */
    val tps: Double get() = if (timeMs <= 0) 0.0 else (moveCount - 1).coerceAtLeast(0) * 1000.0 / timeMs
}
