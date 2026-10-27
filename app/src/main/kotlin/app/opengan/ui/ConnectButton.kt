package app.opengan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.opengan.ble.DeviceManager

/**
 * One-tap Connect: connects the robot, lets it wake the cube, then connects the cube.
 * Asks for Bluetooth permission first if needed. While running it shows what it is doing.
 */
@Composable
fun ConnectButton(devices: DeviceManager, modifier: Modifier = Modifier) {
    val status by devices.autoStatus.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) devices.autoConnect() else devices.report("Bluetooth permission is needed to find your cube and robot")
    }
    val s = status
    if (s == null) {
        Button(
            onClick = { if (devices.hasPermissions()) devices.autoConnect() else permissionLauncher.launch(devices.requiredPermissions()) },
            modifier = modifier,
        ) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null)
            Text("Connect", Modifier.padding(start = 8.dp))
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text(s, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = devices::cancelAutoConnect) { Text("Cancel") }
        }
    }
}
