package app.opengan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.opengan.MainViewModel
import app.opengan.core.cube.Move

@Composable
fun RobotScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val robotConnected by vm.robotConnected.collectAsStateWithLifecycle()
    val progress by vm.robotProgress.collectAsStateWithLifecycle()
    val scramble by vm.scramble.collectAsStateWithLifecycle()
    val canSolve by vm.canSolve.collectAsStateWithLifecycle()
    val cubeConnected by vm.cubeConnected.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf("") }
    var manualError by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("GAN Robot", style = MaterialTheme.typography.headlineSmall)
        if (!robotConnected) {
            Text("No robot connected. Turn the robot on and connect it from the Devices tab. No smart cube is needed to scramble.")
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Random scramble", style = MaterialTheme.typography.titleMedium)
                Text(Move.format(scramble), fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::newScramble) { Text("New") }
                    Button(
                        onClick = { vm.robotScramble() },
                        enabled = robotConnected && progress == null && vm.canRobotScramble(scramble),
                    ) { Text("Scramble") }
                }
                if (!vm.canRobotScramble(scramble)) {
                    Text("This scramble turns U, which the robot can't do. Tap New for a robot-safe one.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Solve", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        canSolve && cubeConnected -> "The robot undoes every move the smart cube has seen since it was solved."
                        canSolve -> "The robot undoes its scrambles since the cube was last solved."
                        cubeConnected -> "Nothing to solve yet, or the cube was connected already scrambled. Solve it once by hand and tap \"Cube is solved\" on the Timer tab."
                        else -> "Nothing to solve yet. Without a smart cube the robot can only undo its own scrambles."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = vm::robotSolve, enabled = robotConnected && progress == null && canSolve) { Text("Solve") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Manual scramble", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it; manualError = null },
                    label = { Text("Moves, e.g. R F' D2 L B") },
                    isError = manualError != null,
                    supportingText = { manualError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = robotConnected && progress == null && manual.isNotBlank(),
                    onClick = {
                        val moves = runCatching { Move.parseSequence(manual) }.getOrNull()
                        manualError = when {
                            moves == null || moves.isEmpty() -> "Use R, F, D, L, B with optional ' or 2"
                            !vm.canRobotScramble(moves) -> "The robot holds the U face and can't turn it"
                            else -> null
                        }
                        if (manualError == null && moves != null) {
                            vm.setManualScramble(manual)
                            vm.robotScramble(moves)
                        }
                    },
                ) { Text("Send to robot") }
            }
        }

        progress?.let {
            Column {
                Text("Robot is turning… ${it.done}/${it.total}")
                LinearProgressIndicator(progress = { if (it.total == 0) 0f else it.done / it.total.toFloat() }, Modifier.fillMaxWidth())
            }
        }

        Text(
            "Tip: put a connected smart cube in the robot. The timer tracks the scramble and arms itself automatically when the robot finishes.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
