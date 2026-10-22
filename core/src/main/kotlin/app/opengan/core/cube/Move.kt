package app.opengan.core.cube

enum class Face { U, R, F, D, L, B;

    /** Faces on the same axis commute (U/D, R/L, F/B). */
    val axis: Int get() = ordinal % 3

    /** U<->D, R<->L, F<->B. */
    val opposite: Face get() = entries[(ordinal + 3) % 6]
}

/** A face turn. [turns] is the number of clockwise quarter turns: 1, 2 or 3 (= prime). */
data class Move(val face: Face, val turns: Int) {
    init {
        require(turns in 1..3) { "turns must be 1..3" }
    }

    val inverse: Move get() = Move(face, 4 - turns)

    override fun toString(): String = face.name + when (turns) {
        1 -> ""
        2 -> "2"
        else -> "'"
    }

    companion object {
        private val TOKEN = Regex("""^([URFDLB])(2'?|'|)$""")

        fun parse(token: String): Move {
            val m = TOKEN.matchEntire(token.trim()) ?: throw IllegalArgumentException("Unrecognised move: '$token'")
            val face = Face.valueOf(m.groupValues[1])
            val turns = when (m.groupValues[2]) {
                "" -> 1
                "'" -> 3
                else -> 2
            }
            return Move(face, turns)
        }

        fun parseSequence(alg: String): List<Move> =
            alg.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.map(::parse)

        fun format(moves: List<Move>): String = moves.joinToString(" ")
    }
}
