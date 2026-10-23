package app.opengan

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opengan.core.cube.CubieCube
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

    /** When the last solve in this session was saved, for the celebration. */
    private val _lastSolveAt = MutableStateFlow<Long?>(null)
    val lastSolveAt: StateFlow<Long?> = _lastSolveAt

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

    /** Cube moves reported while a robot command runs, to check the robot against. */
    private var recording: MutableList<Move>? = null

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
                recording?.add(e.move)
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

    /**
     * Reset cube: use when the app lost sync and the cube is physically solved.
     * Tells the smart cube (if any) its state is solved, forgets the move history
     * and the cached robot orientation, and starts tracking again from solved.
     */
    fun resetCube() {
        viewModelScope.launch { runCatching { devices.cube.value?.markSolved() } }
        _orientation.value = null
        timer.reset()
        timer.onFacelets(CubieCube.SOLVED)
        _cubeState.value = CubieCube.SOLVED
        timer.setScramble(_scramble.value)
        history = mutableListOf()
        updateCanSolve()
    }

    private companion object {
        /** Tries for each robot step before giving up, when the robot misses turns. */
        const val MAX_ATTEMPTS = 3
    }

    fun canRobotScramble(moves: List<Move> = _scramble.value) = GanRobotProtocol.canExecute(moves)

    fun robotScramble(moves: List<Move> = _scramble.value) {
        if (!canRobotScramble(moves)) return
        runRobot("scramble") { robot ->
            if (devices.cube.value == null) {
                execute(robot, moves)
                return@runRobot
            }
            // The cube may have been put back differently after the last solve, so check every time.
            timer.reset()
            val o = checkOrientation(robot, putBack = true)
            runChecked(robot, moves, o)
            // The timer tracks the scramble in the smart cube's frame.
            timer.setScramble(moves.map(o::toCube))
        }
    }

    /**
     * Have the robot solve the cube by undoing every move since it was last solved.
     * With a smart cube connected, the robot first makes three quick test turns
     * (R F D, one command) to see how the cube sits in it. They join the move
     * history, so the solution undoes them too. If the robot misses a turn, the
     * cube's reports show it and the rest is solved again from the new history.
     * Without a smart cube it undoes the robot's own scrambles.
     */
    fun robotSolve() {
        if (history.isNullOrEmpty()) return
        // Keep the robot's turns from starting or stopping the solve timer.
        timer.reset()
        runRobot("solve", after = { timer.setScramble(_scramble.value) }) { robot ->
            if (devices.cube.value == null) {
                execute(robot, RobotSolve.solutionFor(history ?: error("Cube state unknown")))
                return@runRobot
            }
            val o = checkOrientation(robot, putBack = false)
            repeat(MAX_ATTEMPTS) { attempt ->
                val left = history ?: error("Cube state unknown")
                if (left.isEmpty()) return@runRobot
                observe(robot, RobotSolve.solutionFor(left, o), if (attempt == 0) "Solving" else "Robot missed a turn, finishing the solve")
            }
            if (history?.isEmpty() != true) error("the robot kept missing turns")
        }
    }

    /** Run the orientation check on demand: three test turns and back, in one command. */
    fun robotCheckOrientation() {
        if (devices.cube.value == null) {
            devices.report("Put a connected smart cube in the robot first")
            return
        }
        timer.reset()
        runRobot("orientation check", after = { timer.setScramble(_scramble.value) }) { robot ->
            val o = checkOrientation(robot, putBack = true)
            devices.report(if (o.isIdentity) "Cube is placed as expected" else "Cube orientation found: ${o.describe()}")
        }
    }

    /**
     * Find how the cube sits in the robot: turn R, F and D (and back, with [putBack])
     * in one command and match the smart cube's reports. A missed turn shifts the
     * pairing, so that attempt is discarded and the check runs again.
     */
    private suspend fun checkOrientation(robot: GanRobotClient, putBack: Boolean): RobotOrientation {
        val test = RobotOrientation.TEST_TURNS + if (putBack) RobotOrientation.TEST_TURNS.reversed().map { it.inverse } else emptyList()
        val sinceStart = mutableListOf<Move>()
        repeat(MAX_ATTEMPTS) { attempt ->
            val seen = observe(robot, test, if (attempt == 0) "Checking how the cube sits in the robot" else "Robot missed a turn, checking again")
            sinceStart += seen
            val o = RobotOrientation.fromObservations(GanRobotProtocol.executed(test), seen)
            if (o != null) {
                _orientation.value = o
                // Earlier attempts may have left turns behind: undo them too.
                if (putBack) RobotSolve.solutionFor(sinceStart, o).takeIf { it.isNotEmpty() }?.let { runChecked(robot, it, o) }
                return o
            }
            if (seen.isEmpty()) error("the smart cube didn't report any turns. Is it the cube in the robot?")
        }
        error("the robot kept missing turns while checking the cube's orientation")
    }

    /**
     * Run robot [moves] and check them against the smart cube's reports. When the
     * robot missed a turn, send a correction so the cube still ends up as intended.
     */
    private suspend fun runChecked(robot: GanRobotClient, moves: List<Move>, o: RobotOrientation) {
        var todo = moves
        repeat(MAX_ATTEMPTS) { attempt ->
            val expected = GanRobotProtocol.executed(todo).map(o::toCube)
            val seen = observe(robot, todo, if (attempt == 0) "Robot is turning" else "Robot missed a turn, fixing it")
            val fix = RobotOrientation.correction(expected, seen)
            if (CubieCube.SOLVED.apply(fix).isSolved) return
            todo = RobotSolve.forRobot(fix, o)
        }
        error("the robot kept missing turns")
    }

    /** Run a robot command and return the cube moves the smart cube reported meanwhile. */
    private suspend fun observe(robot: GanRobotClient, moves: List<Move>, label: String): List<Move> {
        val turns = GanRobotProtocol.executed(moves).size
        val seen = mutableListOf<Move>()
        recording = seen
        try {
            execute(robot, moves, label)
            // BLE reports can trail the robot's idle status slightly.
            withTimeoutOrNull(1000) { while (seen.size < turns) delay(10) }
        } finally {
            recording = null
        }
        return seen.toList()
    }

    private suspend fun execute(robot: GanRobotClient, moves: List<Move>, label: String = "Robot is turning") {
        robot.scramble(moves) { done, total -> _robotProgress.value = RobotProgress(done, total, label) }
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
        _lastSolveAt.value = SystemClock.elapsedRealtime()
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
