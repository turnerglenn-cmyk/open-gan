package app.opengan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opengan.R
import androidx.compose.ui.graphics.vector.ImageVector
import app.opengan.MainViewModel

private enum class Tab(val label: String, val icon: ImageVector, val color: Color) {
    TIMER("Timer", Icons.Filled.Timer, CubeColors.Blue),
    ROBOT("Robot", Icons.Filled.SmartToy, CubeColors.Orange),
    DEVICES("Devices", Icons.Filled.Bluetooth, CubeColors.Green),
    SUPPORT("Support", Icons.Filled.Favorite, CubeColors.Red),
}

@Composable
fun OpenGanRoot(vm: MainViewModel) {
    OpenGanTheme {
        var tab by rememberSaveable { mutableStateOf(Tab.TIMER) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            vm.devices.messages.collect { snackbar.showSnackbar(it) }
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { Header() },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                indicatorColor = t.color,
                                selectedTextColor = t.color,
                            ),
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

/** App title with the cube logo and a strip of the six sticker colours. */
@Composable
private fun Header() {
    Column(Modifier.statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CubeMark(30.dp)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        }
        Row(Modifier.fillMaxWidth().height(5.dp)) {
            listOf(CubeColors.Red, CubeColors.Orange, CubeColors.Yellow, CubeColors.Green, CubeColors.Blue).forEach {
                Box(Modifier.weight(1f).fillMaxHeight().background(it))
            }
        }
    }
}
