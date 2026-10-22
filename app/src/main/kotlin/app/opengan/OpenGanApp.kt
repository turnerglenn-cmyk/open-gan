package app.opengan

import android.app.Application
import app.opengan.ble.DeviceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class OpenGanApp : Application() {
    /** Lives as long as the process so connections survive configuration changes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var devices: DeviceManager
        private set
    lateinit var solves: SolveStore
        private set

    override fun onCreate() {
        super.onCreate()
        devices = DeviceManager(this, appScope)
        solves = SolveStore(filesDir.resolve("solves.tsv"))
    }
}
