package app.opengan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.opengan.MainViewModel

private enum class Tab(val label: String, val icon: ImageVector) {
    TIMER("Timer", Icons.Filled.Timer),
    ROBOT("Robot", Icons.Filled.SmartToy),
    DEVICES("Devices", Icons.Filled.Bluetooth),
    SUPPORT("Support", Icons.Filled.Favorite),
}

@Composable
fun OpenGanRoot(vm: MainViewModel) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        var tab by rememberSaveable { mutableStateOf(Tab.TIMER) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            vm.devices.messages.collect { snackbar.showSnackbar(it) }
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when (tab) {
                Tab.TIMER -> TimerScreen(vm, modifier)
                Tab.ROBOT -> RobotScreen(vm, modifier)
                Tab.DEVICES -> DevicesScreen(vm, modifier)
                Tab.SUPPORT -> SupportScreen(modifier)
            }
        }
    }
}
