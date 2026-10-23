package app.opengan.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** "Reset cube" for when the app has lost sync: asks first, since it forgets the move history. */
@Composable
fun ResetCubeButton(onReset: () -> Unit, enabled: Boolean = true) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { confirming = true }, enabled = enabled) { Text("Reset cube") }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Reset cube?") },
            text = {
                Text(
                    "Use this when the app is out of sync with your cube. Solve the cube by hand first: " +
                        "the app will treat it as solved and forget the moves it was tracking.",
                )
            },
            confirmButton = { TextButton(onClick = { confirming = false; onReset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
