package app.opengan.core.gan

import app.opengan.core.cube.CubieCube
import app.opengan.core.cube.Face
import app.opengan.core.cube.Move

enum class GanCubeCommand { REQUEST_HARDWARE, REQUEST_FACELETS, REQUEST_BATTERY, REQUEST_RESET }

sealed interface GanCubeEvent {
    /** Host clock time (ms, monotonic) when the BLE notification arrived. */
    val timestamp: Long

    data class MoveEvent(
        override val timestamp: Long,
        /** 0..255, increments on every state change. */
        val serial: Int,
        val face: Face,
        /** 0 = clockwise, 1 = counter-clockwise. */
        val direction: Int,
        /** Host time, or null when this move was missed and recovered from move history. */
        val localTimestamp: Long?,
        /** Cube hardware clock, may be null for recovered moves on Gen3/Gen4. */
        val cubeTimestamp: Long?,
    ) : GanCubeEvent {
        val move: Move get() = Move(face, if (direction == 0) 1 else 3)
    }

    data class FaceletsEvent(
        override val timestamp: Long,
        val serial: Int,
        val state: CubieCube,
    ) : GanCubeEvent {
        val facelets: String get() = state.toFacelets()
    }

    data class GyroEvent(
        override val timestamp: Long,
        val qx: Double, val qy: Double, val qz: Double, val qw: Double,
        val vx: Int, val vy: Int, val vz: Int,
    ) : GanCubeEvent

    data class BatteryEvent(override val timestamp: Long, val batteryLevel: Int) : GanCubeEvent

    data class HardwareEvent(
        override val timestamp: Long,
        val hardwareName: String?,
        val softwareVersion: String?,
        val hardwareVersion: String?,
        val productDate: String? = null,
        val gyroSupported: Boolean = false,
    ) : GanCubeEvent

    data class DisconnectEvent(override val timestamp: Long) : GanCubeEvent
}
