package app.opengan.core.timer

import kotlin.math.roundToLong

/** A move with both clocks, as collected during a solve. */
data class TimedMove(val move: String, val localTimestamp: Long?, val cubeTimestamp: Long?)

/**
 * Fits cube hardware timestamps onto the host clock with linear regression,
 * filling gaps for recovered moves. Cube timestamps are far more precise than
 * BLE notification arrival times, which jitter by tens of milliseconds.
 *
 * The idea of timing from the cube's own clock was pioneered by Chen Shuang in
 * csTimer. This port follows gan-web-bluetooth's cubeTimestampLinearFit (MIT License).
 *
 * @return moves with cubeTimestamp rebased so the first move is at 0.
 */
object TimestampFit {

    fun fit(input: List<TimedMove>): List<TimedMove> {
        if (input.isEmpty()) return emptyList()
        val moves = input.toMutableList()
        if (moves.size >= 2) {
            for (i in moves.size - 1 downTo 1) {
                val cur = moves[i].cubeTimestamp
                if (cur != null && moves[i - 1].cubeTimestamp == null) moves[i - 1] = moves[i - 1].copy(cubeTimestamp = cur - 50)
            }
            for (i in 0 until moves.size - 1) {
                val cur = moves[i].cubeTimestamp
                if (cur != null && moves[i + 1].cubeTimestamp == null) moves[i + 1] = moves[i + 1].copy(cubeTimestamp = cur + 50)
            }
        }
        val (slope, intercept) = linregress(
            moves.map { it.cubeTimestamp?.toDouble() },
            moves.map { it.localTimestamp?.toDouble() },
        )
        val base = moves[0].cubeTimestamp ?: 0L
        val first = (slope * base + intercept).roundToLong()
        return moves.map { m ->
            val ts = m.cubeTimestamp ?: base
            m.copy(cubeTimestamp = (slope * ts + intercept).roundToLong() - first)
        }
    }

    /** Solve time in ms, from the first move to the last, using fitted cube timestamps. */
    fun solveTime(moves: List<TimedMove>): Long? {
        if (moves.isEmpty()) return null
        val fitted = fit(moves)
        return fitted.last().cubeTimestamp!! - fitted.first().cubeTimestamp!!
    }

    private fun linregress(x: List<Double?>, y: List<Double?>): Pair<Double, Double> {
        var sx = 0.0; var sy = 0.0; var sxy = 0.0; var sxx = 0.0; var n = 0
        for (i in x.indices) {
            val xi = x[i] ?: continue
            val yi = y[i] ?: continue
            n++; sx += xi; sy += yi; sxy += xi * yi; sxx += xi * xi
        }
        val varX = n * sxx - sx * sx
        val covXY = n * sxy - sx * sy
        val slope = if (varX < 1e-3) 1.0 else covXY / varX
        val intercept = if (n < 1) 0.0 else sy / n - slope * sx / n
        return slope to intercept
    }
}
