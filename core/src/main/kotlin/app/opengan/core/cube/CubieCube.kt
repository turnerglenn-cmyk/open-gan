package app.opengan.core.cube

/**
 * 3x3 cube state at the cubie level, using Kociemba's conventions:
 *
 * Corners: URF, UFL, ULB, UBR, DFR, DLF, DBL, DRB
 * Edges:   UR, UF, UL, UB, DR, DF, DL, DB, FR, FL, BL, BR
 *
 * GAN smart cubes report their state in exactly this layout, so the
 * cube's FACELETS event can be compared directly with locally tracked state.
 */
data class CubieCube(
    val cp: List<Int> = (0..7).toList(),
    val co: List<Int> = List(8) { 0 },
    val ep: List<Int> = (0..11).toList(),
    val eo: List<Int> = List(12) { 0 },
) {
    init {
        require(cp.size == 8 && co.size == 8 && ep.size == 12 && eo.size == 12) { "Bad cubie array sizes" }
    }

    val isSolved: Boolean get() = this == SOLVED

    /** Compose this state with [other], i.e. apply [other] after this. */
    operator fun times(other: CubieCube): CubieCube = CubieCube(
        cp = List(8) { cp[other.cp[it]] },
        co = List(8) { (co[other.cp[it]] + other.co[it]) % 3 },
        ep = List(12) { ep[other.ep[it]] },
        eo = List(12) { (eo[other.ep[it]] + other.eo[it]) % 2 },
    )

    fun apply(move: Move): CubieCube {
        var c = this
        repeat(move.turns) { c = c * BASIC_MOVES[move.face.ordinal] }
        return c
    }

    fun apply(moves: Iterable<Move>): CubieCube = moves.fold(this) { c, m -> c.apply(m) }

    fun apply(alg: String): CubieCube = apply(Move.parseSequence(alg))

    /** Kociemba facelet string, e.g. "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB". */
    fun toFacelets(): String {
        val faces = "URFDLB"
        val f = CharArray(54) { faces[it / 9] }
        for (i in 0 until 8) for (p in 0 until 3) {
            f[CORNER_FACELETS[i][(p + co[i]) % 3]] = faces[CORNER_FACELETS[cp[i]][p] / 9]
        }
        for (i in 0 until 12) for (p in 0 until 2) {
            f[EDGE_FACELETS[i][(p + eo[i]) % 2]] = faces[EDGE_FACELETS[ep[i]][p] / 9]
        }
        return String(f)
    }

    companion object {
        val SOLVED = CubieCube()
        const val SOLVED_FACELETS = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB"

        private val CORNER_FACELETS = arrayOf(
            intArrayOf(8, 9, 20), intArrayOf(6, 18, 38), intArrayOf(0, 36, 47), intArrayOf(2, 45, 11),
            intArrayOf(29, 26, 15), intArrayOf(27, 44, 24), intArrayOf(33, 53, 42), intArrayOf(35, 17, 51),
        )
        private val EDGE_FACELETS = arrayOf(
            intArrayOf(5, 10), intArrayOf(7, 19), intArrayOf(3, 37), intArrayOf(1, 46),
            intArrayOf(32, 16), intArrayOf(28, 25), intArrayOf(30, 43), intArrayOf(34, 52),
            intArrayOf(23, 12), intArrayOf(21, 41), intArrayOf(50, 39), intArrayOf(48, 14),
        )

        /** Quarter-turn clockwise moves in U R F D L B order. */
        private val BASIC_MOVES = listOf(
            CubieCube(
                listOf(3, 0, 1, 2, 4, 5, 6, 7), List(8) { 0 },
                listOf(3, 0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11), List(12) { 0 },
            ),
            CubieCube(
                listOf(4, 1, 2, 0, 7, 5, 6, 3), listOf(2, 0, 0, 1, 1, 0, 0, 2),
                listOf(8, 1, 2, 3, 11, 5, 6, 7, 4, 9, 10, 0), List(12) { 0 },
            ),
            CubieCube(
                listOf(1, 5, 2, 3, 0, 4, 6, 7), listOf(1, 2, 0, 0, 2, 1, 0, 0),
                listOf(0, 9, 2, 3, 4, 8, 6, 7, 1, 5, 10, 11), listOf(0, 1, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0),
            ),
            CubieCube(
                listOf(0, 1, 2, 3, 5, 6, 7, 4), List(8) { 0 },
                listOf(0, 1, 2, 3, 5, 6, 7, 4, 8, 9, 10, 11), List(12) { 0 },
            ),
            CubieCube(
                listOf(0, 2, 6, 3, 4, 1, 5, 7), listOf(0, 1, 2, 0, 0, 2, 1, 0),
                listOf(0, 1, 10, 3, 4, 5, 9, 7, 8, 2, 6, 11), List(12) { 0 },
            ),
            CubieCube(
                listOf(0, 1, 3, 7, 4, 5, 2, 6), listOf(0, 0, 1, 2, 0, 0, 2, 1),
                listOf(0, 1, 2, 11, 4, 5, 6, 10, 8, 9, 3, 7), listOf(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 1),
            ),
        )
    }
}
