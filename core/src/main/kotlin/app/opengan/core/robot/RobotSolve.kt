package app.opengan.core.robot

import app.opengan.core.cube.Face
import app.opengan.core.cube.Move

/**
 * Builds a solution the GAN Robot can execute (no U turns) by undoing the
 * moves made since the cube was last solved.
 *
 * Each U turn in the undo sequence is replaced by an equivalent U-free sequence:
 * U = (R L' F2 B2 R L') D (R L' F2 B2 R L'). The result is then simplified.
 *
 * This needs the move history, so it works after robot scrambles and while a smart
 * cube has been tracked from solved. Solving an arbitrary state needs a real
 * solver (planned: two-phase, U-free).
 */
object RobotSolve {

    private val SETUP = Move.parseSequence("R L' F2 B2 R L'")

    /** U-free equivalent of [move]. Faces other than U are returned unchanged. */
    fun withoutU(move: Move): List<Move> =
        if (move.face != Face.U) listOf(move) else SETUP + Move(Face.D, move.turns) + SETUP

    fun solutionFor(history: List<Move>): List<Move> =
        simplify(simplify(history).reversed().map { it.inverse }.flatMap(::withoutU))

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
