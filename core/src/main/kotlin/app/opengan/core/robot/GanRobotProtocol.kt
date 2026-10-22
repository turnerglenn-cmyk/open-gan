package app.opengan.core.robot

import app.opengan.core.cube.Face
import app.opengan.core.cube.Move

/**
 * GAN Robot wire protocol.
 *
 * The robot grips the cube by its U face, so it can only turn R, F, D, L and B.
 * A scramble is written to characteristic FFF3 as bytes holding two moves each
 * (high nibble first, 0xF = no move), at most 16 bytes per write. The status
 * characteristic FFF2 reads non-zero while the robot is turning and 0 when idle.
 *
 * Protocol facts documented by rsimp/gan-scrambler; this is an independent implementation.
 */
object GanRobotProtocol {
    const val SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb"
    const val SCRAMBLE_CHARACTERISTIC = "0000fff3-0000-1000-8000-00805f9b34fb"
    const val STATUS_CHARACTERISTIC = "0000fff2-0000-1000-8000-00805f9b34fb"
    const val DEVICE_INFO_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb"
    const val MODEL_NUMBER_CHARACTERISTIC = "00002a24-0000-1000-8000-00805f9b34fb"
    const val MODEL_NUMBER = "GAN ROBOTCUBE"

    const val MAX_BYTES_PER_WRITE = 16
    private const val NO_MOVE = 0xF

    /** Faces the robot can turn, in wire order. */
    val ROBOT_FACES = listOf(Face.R, Face.F, Face.D, Face.L, Face.B)

    fun isRobotModel(modelNumber: String) = modelNumber.trim().equals(MODEL_NUMBER, ignoreCase = true)

    fun canExecute(moves: List<Move>) = moves.none { it.face == Face.U }

    /** Wire code: face index * 3 + (0 = CW, 1 = double, 2 = CCW). */
    fun code(move: Move): Int {
        val faceIndex = ROBOT_FACES.indexOf(move.face)
        require(faceIndex >= 0) { "The GAN Robot cannot turn the ${move.face} face" }
        return faceIndex * 3 + (move.turns - 1)
    }

    /** The turns the robot physically makes, in order: doubles become two quarter turns when split. */
    fun executed(moves: List<Move>, splitDoubles: Boolean = true): List<Move> =
        if (!splitDoubles) moves
        else moves.flatMap { if (it.turns == 2) listOf(Move(it.face, 1), Move(it.face, 1)) else listOf(it) }

    /**
     * Encode a scramble into the sequence of writes to send.
     *
     * @param splitDoubles the robot occasionally fails on double turns, so by
     *   default they are sent as two quarter turns.
     */
    fun encode(moves: List<Move>, splitDoubles: Boolean = true): List<ByteArray> {
        val codes = executed(moves, splitDoubles).map(::code)
        val bytes = codes.chunked(2).map { pair -> ((pair[0] shl 4) or (pair.getOrNull(1) ?: NO_MOVE)).toByte() }
        return bytes.chunked(MAX_BYTES_PER_WRITE).map { it.toByteArray() }
    }
}
