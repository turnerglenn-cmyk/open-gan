package app.opengan.core.scramble

import app.opengan.core.cube.Face
import app.opengan.core.cube.Move
import kotlin.random.Random

/**
 * Random-move scrambles.
 *
 * [robotSafe] scrambles never turn U, because the GAN Robot holds the cube by it.
 * Random-state scrambles (two-phase solver) are planned; random-move with 25
 * moves over 5 faces already gives a thorough scramble for practice.
 */
class Scrambler(private val random: Random = Random.Default) {

    fun randomMoves(length: Int = 25, robotSafe: Boolean = true): List<Move> {
        val faces = if (robotSafe) Face.entries.filter { it != Face.U } else Face.entries
        val out = mutableListOf<Move>()
        while (out.size < length) {
            val face = faces[random.nextInt(faces.size)]
            val last = out.lastOrNull()
            val beforeLast = out.getOrNull(out.size - 2)
            // No repeated face, and no X Y X on the same axis (e.g. R L R), which would cancel.
            if (last != null && last.face == face) continue
            // Robot scrambles open with one turn per axis, so a smart cube's reports of
            // the first moves alone show how it is placed in the robot.
            if (robotSafe && out.size < 3 && out.any { it.face.axis == face.axis }) continue
            if (last != null && beforeLast != null && last.face.axis == face.axis && beforeLast.face == face) continue
            out += Move(face, random.nextInt(1, 4))
        }
        return out
    }
}
