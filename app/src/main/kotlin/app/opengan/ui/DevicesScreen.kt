package app.opengan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.opengan.MainViewModel

@Composable
fun DevicesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val devices = vm.devices
    val scanning by devices.scanning.collectAsStateWithLifecycle()
    val results by devices.scanResults.collectAsStateWithLifecycle()
    val cube by devices.cube.collectAsStateWithLifecycle()
    val robot by devices.robot.collectAsStateWithLifecycle()
    val connecting by devices.connecting.collectAsStateWithLifecycle()
    val cubeInfo by vm.cubeInfo.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) devices.startScan() else devices.report("Bluetooth permission is needed to find your cube and robot")
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Devices", style = MaterialTheme.typography.headlineSmall)
        if (cube == null || robot == null) {
            Text(
                "Turn the robot on with the smart cube inside and tap Connect: the robot connects, wakes the cube, and the cube connects.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ConnectButton(devices, Modifier.fillMaxWidth())
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Smart cube", style = MaterialTheme.typography.titleMedium)
                val c = cube
                if (c == null) Text("Not connected")
                else {
                    Text("${c.name} · ${c.protocol.name.lowercase()} protocol")
                    Text(listOfNotNull(cubeInfo.hardware, cubeInfo.firmware?.let { "fw $it" }, cubeInfo.battery?.let { "battery $it%" }).joinToString(" · "))
                    TextButton(onClick = devices::disconnectCube) { Text("Disconnect") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("GAN Robot", style = MaterialTheme.typography.titleMedium)
                val r = robot
                if (r == null) Text("Not connected")
                else {
                    Text(r.name)
                    TextButton(onClick = devices::disconnectRobot) { Text("Disconnect") }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                when {
                    scanning -> devices.stopScan()
                    devices.hasPermissions() -> devices.startScan()
                    else -> permissionLauncher.launch(devices.requiredPermissions())
                }
            }) { Text(if (scanning) "Stop" else "Pick a device") }
            if (scanning || connecting != null) CircularProgressIndicator()
            connecting?.let { Text("Connecting to $it…") }
        }
        if (!devices.bluetoothEnabled) Text("Bluetooth is off. Turn it on in system settings.", color = MaterialTheme.colorScheme.error)

        LazyColumn {
            items(results, key = { it.address }) { d ->
                ListItem(
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text("${d.address} · ${d.rssi} dBm") },
                    modifier = Modifier.clickable(enabled = connecting == null) { devices.connect(d) },
                )
            }
        }
    }
}
