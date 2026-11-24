package app.opengan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shown while a cube or robot that dropped on its own is being reconnected in the
 * background. The timer and session carry on meanwhile. Stop gives up and disconnects.
 */
@Composable
fun ReconnectingBanner(cube: Boolean, robot: Boolean, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val what = when {
        cube && robot -> "Cube and robot"
        cube -> "Cube"
        else -> "Robot"
    }
    Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                "$what reconnecting…" + if (cube) " Twist the cube if it went to sleep." else "",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onStop) { Text("Stop") }
        }
    }
}
