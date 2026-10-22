package app.opengan.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.opengan.MainViewModel
import app.opengan.TapTimer
import app.opengan.core.cube.Move
import app.opengan.core.timer.Penalty
import app.opengan.core.timer.SmartTimer.Phase
import app.opengan.core.timer.Stats
import kotlinx.coroutines.delay

@Composable
fun TimerScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val scramble by vm.scramble.collectAsStateWithLifecycle()
    val solves by vm.solves.collectAsStateWithLifecycle()
    val cubeConnected by vm.cubeConnected.collectAsStateWithLifecycle()
    val robotConnected by vm.robotConnected.collectAsStateWithLifecycle()
    val robotProgress by vm.robotProgress.collectAsStateWithLifecycle()
    val cube by vm.cubeState.collectAsStateWithLifecycle()
    val tap by vm.tapTimer.collectAsStateWithLifecycle()
    val canSolve by vm.canSolve.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ScrambleText(scramble, applied = (phase as? Phase.Scrambling)?.applied ?: if (phase is Phase.Ready) scramble.size else 0)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::newScramble) { Text("New scramble") }
            if (robotConnected) {
                OutlinedButton(onClick = { vm.robotScramble() }, enabled = robotProgress == null && vm.canRobotScramble(scramble)) {
                    Text(robotProgress?.let { "Robot ${it.done}/${it.total}" } ?: "Robot scramble")
                }
                OutlinedButton(onClick = vm::robotSolve, enabled = robotProgress == null && canSolve) { Text("Solve") }
            }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).clickable(enabled = !cubeConnected) { vm.tapTimerPressed() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (cubeConnected) {
                    BigTime(smartTimeMs(phase))
                    Text(smartHint(phase), style = MaterialTheme.typography.bodyMedium)
                    (phase as? Phase.Finished)?.result?.let {
                        Text("${it.moveCount} moves · %.2f TPS".format(it.tps), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    CubeNet(cube, Modifier.fillMaxWidth(0.6f))
                    Row {
                        TextButton(onClick = vm::armFreeSolve) { Text("Free solve") }
                        TextButton(onClick = vm::markCubeSolved) { Text("Cube is solved") }
                    }
                } else {
                    BigTime(tapTimeMs(tap))
                    Text(
                        if (tap is TapTimer.Running) "Tap to stop" else "Tap to start · connect a smart cube for automatic timing",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        StatsRow(solves)
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().height(160.dp)) {
            val reversed = solves.withIndex().reversed()
            itemsIndexed(reversed, key = { _, it -> it.index }) { _, (index, s) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}.", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
                    Text(
                        Stats.format(s.effectiveMs) + if (s.penalty == Penalty.PLUS_TWO) "+" else "",
                        Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                    )
                    TextButton(onClick = { vm.setPenalty(index, if (s.penalty == Penalty.PLUS_TWO) Penalty.NONE else Penalty.PLUS_TWO) }) { Text("+2") }
                    TextButton(onClick = { vm.setPenalty(index, if (s.penalty == Penalty.DNF) Penalty.NONE else Penalty.DNF) }) { Text("DNF") }
                    TextButton(onClick = { vm.deleteSolve(index) }) { Text("✕") }
                }
            }
        }
    }
}

@Composable
private fun ScrambleText(scramble: List<Move>, applied: Int) {
    val done = MaterialTheme.colorScheme.primary
    val text = buildAnnotatedString {
        scramble.forEachIndexed { i, m ->
            if (i < applied) withStyle(SpanStyle(color = done, fontWeight = FontWeight.Bold)) { append(m.toString()) }
            else append(m.toString())
            append(' ')
        }
    }
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 20.sp, lineHeight = 28.sp)
}

@Composable
private fun BigTime(ms: Long?) {
    Text(Stats.format(ms ?: 0L), fontSize = 72.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light)
}

/** Re-composes about every 30 ms while a timer is running. */
@Composable
private fun rememberNow(running: Boolean): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(running) {
        while (running) {
            now = SystemClock.elapsedRealtime()
            delay(30)
        }
    }
    return now
}

@Composable
private fun smartTimeMs(phase: Phase): Long? {
    val now = rememberNow(phase is Phase.Running)
    return when (phase) {
        is Phase.Running -> (now - phase.startedAt).coerceAtLeast(0)
        is Phase.Finished -> phase.result.timeMs
        else -> 0L
    }
}

@Composable
private fun tapTimeMs(t: TapTimer): Long {
    val now = rememberNow(t is TapTimer.Running)
    return when (t) {
        is TapTimer.Running -> now - t.startedAt
        is TapTimer.Stopped -> t.timeMs
        TapTimer.Idle -> 0L
    }
}

private fun smartHint(phase: Phase) = when (phase) {
    Phase.Idle -> "Waiting for a scramble"
    is Phase.Scrambling -> "Scramble the cube (${phase.applied}/${phase.scramble.size})"
    is Phase.Ready -> "Ready: the timer starts on your first turn"
    is Phase.Running -> "${phase.moveCount} moves"
    is Phase.Finished -> "Solved! Turn the cube to start the next scramble"
}

@Composable
private fun StatsRow(solves: List<app.opengan.core.timer.Solve>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        StatCell("Best", Stats.best(solves))
        StatCell("Ao5", Stats.averageOf(solves, 5), solves.size >= 5)
        StatCell("Ao12", Stats.averageOf(solves, 12), solves.size >= 12)
        StatCell("Mean", Stats.mean(solves))
    }
}

@Composable
private fun StatCell(label: String, ms: Long?, available: Boolean = ms != null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(if (available) Stats.format(ms) else "–", fontFamily = FontFamily.Monospace)
    }
}
