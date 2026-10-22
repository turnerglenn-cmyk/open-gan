package app.opengan.core.timer

enum class Penalty { NONE, PLUS_TWO, DNF }

data class Solve(
    val dateEpochMs: Long,
    val timeMs: Long,
    val scramble: String,
    val solution: String = "",
    val penalty: Penalty = Penalty.NONE,
) {
    /** Time including penalty; null for DNF. */
    val effectiveMs: Long? get() = when (penalty) {
        Penalty.NONE -> timeMs
        Penalty.PLUS_TWO -> timeMs + 2000
        Penalty.DNF -> null
    }

    /** One line, tab separated. Scrambles and solutions never contain tabs. */
    fun serialize(): String = listOf(dateEpochMs, timeMs, penalty.name, scramble, solution).joinToString("\t")

    companion object {
        fun parse(line: String): Solve? {
            val p = line.split('\t')
            if (p.size < 4) return null
            return Solve(
                dateEpochMs = p[0].toLongOrNull() ?: return null,
                timeMs = p[1].toLongOrNull() ?: return null,
                penalty = runCatching { Penalty.valueOf(p[2]) }.getOrDefault(Penalty.NONE),
                scramble = p[3],
                solution = p.getOrElse(4) { "" },
            )
        }
    }
}

object Stats {
    /**
     * WCA-style average of the last [n] solves: drop best and worst (5% each
     * side for larger n), more than one DNF in the trimmed set means DNF.
     * Returns null if there are fewer than [n] solves or the average is DNF.
     */
    fun averageOf(solves: List<Solve>, n: Int): Long? {
        if (solves.size < n || n < 3) return null
        val window = solves.takeLast(n).map { it.effectiveMs ?: Long.MAX_VALUE }.sorted()
        val trim = maxOf(1, (n * 0.05).let { kotlin.math.ceil(it).toInt() })
        val kept = window.subList(trim, n - trim)
        if (kept.any { it == Long.MAX_VALUE }) return null
        return kept.sum() / kept.size
    }

    fun mean(solves: List<Solve>): Long? {
        val times = solves.mapNotNull { it.effectiveMs }
        return if (times.isEmpty()) null else times.sum() / times.size
    }

    fun best(solves: List<Solve>): Long? = solves.mapNotNull { it.effectiveMs }.minOrNull()

    fun format(ms: Long?): String {
        if (ms == null) return "DNF"
        val minutes = ms / 60000
        val seconds = (ms % 60000) / 1000
        val hundredths = (ms % 1000) / 10
        return if (minutes > 0) "%d:%02d.%02d".format(minutes, seconds, hundredths)
        else "%d.%02d".format(seconds, hundredths)
    }
}
