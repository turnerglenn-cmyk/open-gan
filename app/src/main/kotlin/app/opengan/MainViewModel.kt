package app.opengan

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opengan.core.cube.CubieCube
import app.opengan.core.cube.Move
import app.opengan.core.gan.GanCubeEvent
import app.opengan.core.robot.GanRobotProtocol
import app.opengan.core.scramble.Scrambler
import app.opengan.core.timer.Penalty
import app.opengan.core.timer.SmartTimer
import app.opengan.core.timer.Solve
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CubeInfo(val battery: Int? = null, val hardware: String? = null, val firmware: String? = null)

data class RobotProgress(val done: Int, val total: Int)

/** Manual (on-screen) timer used when no smart cube is connected. */
sealed interface TapTimer {
    data object Idle : TapTimer
    data class Running(val startedAt: Long) : TapTimer
    data class Stopped(val timeMs: Long) : TapTimer
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OpenGanApp
    val devices = app.devices
    private val scrambler = Scrambler()

    private val _phase = MutableStateFlow<SmartTimer.Phase>(SmartTimer.Phase.Idle)
    val phase: StateFlow<SmartTimer.Phase> = _phase
    private val timer = SmartTimer { p ->
        _phase.value = p
        if (p is SmartTimer.Phase.Finished) onSmartSolve(p)
    }

    private val _cubeState = MutableStateFlow(CubieCube.SOLVED)
    val cubeState: StateFlow<CubieCube> = _cubeState

    private val _cubeInfo = MutableStateFlow(CubeInfo())
    val cubeInfo: StateFlow<CubeInfo> = _cubeInfo

    private val _scramble = MutableStateFlow<List<Move>>(emptyList())
    val scramble: StateFlow<List<Move>> = _scramble

    private val _solves = MutableStateFlow<List<Solve>>(emptyList())
    val solves: StateFlow<List<Solve>> = _solves

    private val _robotProgress = MutableStateFlow<RobotProgress?>(null)
    val robotProgress: StateFlow<RobotProgress?> = _robotProgress

    private val _tapTimer = MutableStateFlow<TapTimer>(TapTimer.Idle)
    val tapTimer: StateFlow<TapTimer> = _tapTimer

    val cubeConnected = devices.cube.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val robotConnected = devices.robot.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch { _solves.value = app.solves.load() }
        viewModelScope.launch {
            devices.cube.flatMapLatest { it?.events ?: emptyFlow() }.collect { onCubeEvent(it) }
        }
        newScramble()
    }

    private fun onCubeEvent(e: GanCubeEvent) {
        when (e) {
            is GanCubeEvent.MoveEvent -> {
                // After a solve the result stays on screen until the cube is turned again.
                if (timer.phase is SmartTimer.Phase.Finished) timer.setScramble(_scramble.value)
                timer.onMove(e.move, e.localTimestamp, e.cubeTimestamp)
            }
            is GanCubeEvent.FaceletsEvent -> timer.onFacelets(e.state)
            is GanCubeEvent.BatteryEvent -> _cubeInfo.update { it.copy(battery = e.batteryLevel) }
            is GanCubeEvent.HardwareEvent -> _cubeInfo.update { it.copy(hardware = e.hardwareName, firmware = e.softwareVersion) }
            is GanCubeEvent.DisconnectEvent -> _cubeInfo.value = CubeInfo()
            is GanCubeEvent.GyroEvent -> Unit
        }
        _cubeState.value = timer.cube
    }

    fun newScramble() {
        newScrambleKeepingTimer()
        timer.setScramble(_scramble.value)
        _tapTimer.value = TapTimer.Idle
    }

    fun setManualScramble(text: String): String? {
        val moves = runCatching { Move.parseSequence(text) }.getOrElse { return it.message }
        if (moves.isEmpty()) return "Enter at least one move"
        _scramble.value = moves
        timer.setScramble(moves)
        return null
    }

    fun armFreeSolve() = timer.armFree()

    /** Use when the cube is physically solved but the app thinks otherwise. */
    fun markCubeSolved() {
        viewModelScope.launch { runCatching { devices.cube.value?.markSolved() } }
        timer.reset()
        timer.onFacelets(CubieCube.SOLVED)
        _cubeState.value = CubieCube.SOLVED
        timer.setScramble(_scramble.value)
    }

    fun canRobotScramble(moves: List<Move> = _scramble.value) = GanRobotProtocol.canExecute(moves)

    fun robotScramble(moves: List<Move> = _scramble.value) {
        val robot = devices.robot.value ?: return
        if (_robotProgress.value != null || !canRobotScramble(moves)) return
        viewModelScope.launch {
            try {
                robot.scramble(moves) { done, total -> _robotProgress.value = RobotProgress(done, total) }
            } catch (e: Exception) {
                devices.report("Robot scramble failed: ${e.message}")
            } finally {
                _robotProgress.value = null
            }
        }
    }

    fun tapTimerPressed() {
        _tapTimer.value = when (val t = _tapTimer.value) {
            TapTimer.Idle, is TapTimer.Stopped -> TapTimer.Running(SystemClock.elapsedRealtime())
            is TapTimer.Running -> {
                val time = SystemClock.elapsedRealtime() - t.startedAt
                save(Solve(System.currentTimeMillis(), time, Move.format(_scramble.value)))
                newScrambleKeepingTimer()
                timer.setScramble(_scramble.value)
                TapTimer.Stopped(time)
            }
        }
    }

    /** Robot-safe (no U turns) whenever a robot is connected, so the scramble can be sent to it. */
    private fun newScrambleKeepingTimer() {
        _scramble.value = scrambler.randomMoves(25, robotSafe = devices.robot.value != null)
    }

    private fun onSmartSolve(p: SmartTimer.Phase.Finished) {
        save(Solve(System.currentTimeMillis(), p.result.timeMs, p.result.scramble, p.result.solution))
        newScrambleKeepingTimer()
    }

    private fun save(solve: Solve) {
        _solves.update { it + solve }
        viewModelScope.launch { app.solves.append(solve) }
    }

    fun setPenalty(index: Int, penalty: Penalty) = editSolves { list ->
        list.mapIndexed { i, s -> if (i == index) s.copy(penalty = penalty) else s }
    }

    fun deleteSolve(index: Int) = editSolves { list -> list.filterIndexed { i, _ -> i != index } }

    private fun editSolves(edit: (List<Solve>) -> List<Solve>) {
        val updated = edit(_solves.value)
        _solves.value = updated
        viewModelScope.launch { app.solves.replaceAll(updated) }
    }
}
