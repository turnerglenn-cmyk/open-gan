package app.opengan.core.robot

import app.opengan.core.cube.CubieCube
import app.opengan.core.cube.Face
import app.opengan.core.cube.Move

/**
 * Builds a solution the GAN Robot can execute by undoing the moves made since
 * the cube was last solved.
 *
 * The robot grips one face and cannot turn it. Each turn of that face in the
 * undo sequence is replaced by an equivalent sequence that avoids it, e.g.
 * U = (R L' F2 B2 R L') D (R L' F2 B2 R L'). The result is then simplified and
 * translated into the robot's own face names using the [RobotOrientation].
 *
 * This needs the move history, so it works after robot scrambles and while a smart
 * cube has been tracked from solved. Solving an arbitrary state needs a real
 * solver (planned: two-phase).
 */
object RobotSolve {

    /** For each face, a 6-move setup S with S + opposite + S == face (for any number of turns). */
    private val SETUPS: Map<Face, List<Move>> by lazy { Face.entries.associateWith(::findSetup) }

    private fun findSetup(target: Face): List<Move> {
        val sides = Face.entries.filter { it.axis != target.axis }
        for (p in sides) for (q in sides) {
            if (q.axis == p.axis) continue
            for (pTurns in listOf(1, 3)) {
                val setup = listOf(
                    Move(p, pTurns), Move(p.opposite, 4 - pTurns), Move(q, 2), Move(q.opposite, 2),
                    Move(p, pTurns), Move(p.opposite, 4 - pTurns),
                )
                val works = (1..3).all { t ->
                    CubieCube.SOLVED.apply(setup + Move(target.opposite, t) + setup) == CubieCube.SOLVED.apply(Move(target, t))
                }
                if (works) return setup
            }
        }
        error("No setup found for $target")
    }

    /** Equivalent of [move] that never turns [forbidden]. */
    fun avoiding(move: Move, forbidden: Face): List<Move> =
        if (move.face != forbidden) listOf(move)
        else SETUPS.getValue(forbidden).let { it + Move(forbidden.opposite, move.turns) + it }

    /** U-free equivalent of [move]. */
    fun withoutU(move: Move): List<Move> = avoiding(move, Face.U)

    /**
     * @param history moves since solved, in the cube's frame (the smart cube's
     *   reports), or in the robot's frame when there is no smart cube.
     * @param orientation how the cube sits in the robot; IDENTITY when [history] is in robot frame.
     * @return robot moves (robot face names) that solve the cube.
     */
    fun solutionFor(history: List<Move>, orientation: RobotOrientation = RobotOrientation.IDENTITY): List<Move> {
        val undo = simplify(history).reversed().map { it.inverse }
        val reachable = simplify(undo.flatMap { avoiding(it, orientation.gripped) })
        return reachable.map { orientation.toRobot(it) ?: error("Cannot turn gripped face ${it.face}") }
    }

    /** Merge turns of the same face, including across a turn of the opposite face (R L R = R2 L). */
    fun simplify(moves: List<Move>): List<Move> {
        val out = ArrayList<Pair<Face, Int>>()
        for (m in moves) {
            val top = out.lastOrNull()
            val below = out.getOrNull(out.size - 2)
            val index = when {
                top != null && top.first == m.face -> out.size - 1
                top != null && below != null && top.first.axis == m.face.axis && below.first == m.face -> out.size - 2
                else -> -1
            }
            if (index < 0) {
                out += m.face to m.turns
                continue
            }
            val turns = (out[index].second + m.turns) % 4
            if (turns == 0) out.removeAt(index) else out[index] = m.face to turns
        }
        return out.map { Move(it.first, it.second) }
    }
}
