package app.opengan

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opengan.core.cube.CubieCube
import app.opengan.core.cube.Face
import app.opengan.core.cube.Move
import app.opengan.core.gan.GanCubeEvent
import app.opengan.ble.GanRobotClient
import app.opengan.core.robot.GanRobotProtocol
import app.opengan.core.robot.RobotOrientation
import app.opengan.core.robot.RobotSolve
import app.opengan.core.scramble.Scrambler
import app.opengan.core.timer.Penalty
import app.opengan.core.timer.SmartTimer
import app.opengan.core.timer.Solve
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
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

data class RobotProgress(val done: Int, val total: Int, val label: String = "Robot is turning")

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

    /**
     * Moves made since the cube was last known solved, used to build a robot solution.
     * Null when the state is unknown (e.g. the cube reported a state we didn't track).
     */
    private var history: MutableList<Move>? = mutableListOf()
    private val _canSolve = MutableStateFlow(false)
    val canSolve: StateFlow<Boolean> = _canSolve

    /**
     * How the smart cube sits in the robot, found with test turns. Null until
     * checked; reset whenever the cube or robot (re)connects.
     */
    private val _orientation = MutableStateFlow<RobotOrientation?>(null)
    val orientation: StateFlow<RobotOrientation?> = _orientation

    /** Cube moves seen during an orientation test turn. */
    private var testTurnMoves: MutableList<Move>? = null

    val cubeConnected = devices.cube.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val robotConnected = devices.robot.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch { _solves.value = app.solves.load() }
        viewModelScope.launch {
            devices.cube.flatMapLatest { it?.events ?: emptyFlow() }.collect { onCubeEvent(it) }
        }
        viewModelScope.launch {
            combine(devices.cube, devices.robot) { c, r -> c to r }.collect { _orientation.value = null }
        }
        newScramble()
    }

    private fun onCubeEvent(e: GanCubeEvent) {
        when (e) {
            is GanCubeEvent.MoveEvent -> {
                // After a solve the result stays on screen until the cube is turned again.
                if (timer.phase is SmartTimer.Phase.Finished) timer.setScramble(_scramble.value)
                timer.onMove(e.move, e.localTimestamp, e.cubeTimestamp)
                testTurnMoves?.add(e.move)
                history?.add(e.move)
                if (timer.cube.isSolved) history = mutableListOf()
            }
            is GanCubeEvent.FaceletsEvent -> {
                val tracked = timer.cube
                timer.onFacelets(e.state)
                if (e.state.isSolved) history = mutableListOf() else if (e.state != tracked) history = null
            }
            is GanCubeEvent.BatteryEvent -> _cubeInfo.update { it.copy(battery = e.batteryLevel) }
            is GanCubeEvent.HardwareEvent -> _cubeInfo.update { it.copy(hardware = e.hardwareName, firmware = e.softwareVersion) }
            is GanCubeEvent.DisconnectEvent -> _cubeInfo.value = CubeInfo()
            is GanCubeEvent.GyroEvent -> Unit
        }
        _cubeState.value = timer.cube
        updateCanSolve()
    }

    private fun updateCanSolve() {
        _canSolve.value = history?.isNotEmpty() == true
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
        history = mutableListOf()
        updateCanSolve()
    }

    fun canRobotScramble(moves: List<Move> = _scramble.value) = GanRobotProtocol.canExecute(moves)

    fun robotScramble(moves: List<Move> = _scramble.value) {
        if (!canRobotScramble(moves)) return
        runRobot("scramble") { robot ->
            // The timer tracks the scramble in the smart cube's frame, which depends on how it sits in the robot.
            if (devices.cube.value != null) {
                val o = ensureOrientation(robot)
                timer.setScramble(moves.map(o::toCube))
            }
            execute(robot, moves)
        }
    }

    /**
     * Have the robot solve the cube by undoing every move since it was last solved.
     * With a smart cube connected, the app first finds how the cube sits in the
     * robot (test turns of R, F and D, each undone), then translates the solution
     * into robot faces and works around the face the robot grips.
     * Without a smart cube it undoes the robot's own scrambles.
     */
    fun robotSolve() {
        if (history.isNullOrEmpty()) return
        // Keep the robot's turns from starting or stopping the solve timer.
        timer.reset()
        runRobot("solve", after = { timer.setScramble(_scramble.value) }) { robot ->
            val o = if (devices.cube.value != null) checkOrientation(robot) else RobotOrientation.IDENTITY
            val moves = RobotSolve.solutionFor(history ?: error("Cube state unknown"), o)
            execute(robot, moves)
        }
    }

    /** Run the orientation test on demand, e.g. after putting the cube back in the robot. */
    fun robotCheckOrientation() {
        if (devices.cube.value == null) {
            devices.report("Put a connected smart cube in the robot first")
            return
        }
        timer.reset()
        runRobot("orientation check", after = { timer.setScramble(_scramble.value) }) { robot ->
            val o = checkOrientation(robot)
            devices.report(if (o.isIdentity) "Cube is placed as expected" else "Cube orientation found: ${o.describe()}")
        }
    }

    private suspend fun ensureOrientation(robot: GanRobotClient): RobotOrientation {
        _orientation.value?.let { return it }
        val previous = timer.phase
        timer.reset()
        val o = checkOrientation(robot)
        if (previous !is SmartTimer.Phase.Idle) timer.setScramble(_scramble.value)
        return o
    }

    private suspend fun checkOrientation(robot: GanRobotClient): RobotOrientation {
        val seen = listOf(Face.R, Face.F, Face.D).mapIndexed { i, face ->
            _robotProgress.value = RobotProgress(i, 3, "Checking how the cube sits in the robot")
            testTurn(robot, face)
        }
        return RobotOrientation.fromTestTurns(seen[0], seen[1], seen[2]).also { _orientation.value = it }
    }

    /** Turn one robot face a quarter turn and back, returning the cube move it produced. */
    private suspend fun testTurn(robot: GanRobotClient, face: Face): Move {
        val seen = mutableListOf<Move>()
        testTurnMoves = seen
        try {
            robot.scramble(listOf(Move(face, 1)))
            withTimeoutOrNull(2000) { while (seen.isEmpty()) delay(20) }
            val forward = seen.toList()
            robot.scramble(listOf(Move(face, 3)))
            // Let the undo turn arrive so it isn't mistaken for the next test turn.
            withTimeoutOrNull(2000) { while (seen.size < forward.size + 1) delay(20) }
            return forward.singleOrNull() ?: throw IllegalStateException(
                "Robot $face produced ${forward.size} cube turns (expected 1). Is the connected smart cube the one in the robot?",
            )
        } finally {
            testTurnMoves = null
        }
    }

    private suspend fun execute(robot: GanRobotClient, moves: List<Move>) {
        robot.scramble(moves) { done, total -> _robotProgress.value = RobotProgress(done, total) }
        // With a smart cube connected, its own move events already updated the history.
        if (devices.cube.value == null) {
            history?.addAll(moves)
            if (CubieCube.SOLVED.apply(history.orEmpty()).isSolved) history = mutableListOf()
        }
    }

    private fun runRobot(what: String, after: () -> Unit = {}, block: suspend (GanRobotClient) -> Unit) {
        val robot = devices.robot.value ?: return
        if (_robotProgress.value != null) return
        _robotProgress.value = RobotProgress(0, 1)
        viewModelScope.launch {
            try {
                block(robot)
            } catch (e: Exception) {
                devices.report("Robot $what failed: ${e.message}")
            } finally {
                _robotProgress.value = null
                updateCanSolve()
                after()
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
