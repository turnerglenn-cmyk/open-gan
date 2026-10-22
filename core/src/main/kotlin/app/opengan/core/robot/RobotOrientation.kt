package app.opengan.core.robot

import app.opengan.core.cube.Face
import app.opengan.core.cube.Move

/**
 * How a smart cube sits in the GAN Robot: for each face the robot can turn
 * (in the robot's own R F D L B naming), which cube face actually turns and
 * whether a robot clockwise turn shows up as counter-clockwise on the cube.
 *
 * The smart cube reports moves relative to its own centres (white = U), so
 * unless the cube is placed exactly as the app assumes, robot "R" is not cube "R".
 */
class RobotOrientation private constructor(
    private val robotToCube: Map<Face, Pair<Face, Boolean>>,
) {
    init {
        require(robotToCube.keys == GanRobotProtocol.ROBOT_FACES.toSet()) { "Need a mapping for R F D L B" }
        require(robotToCube.values.map { it.first }.toSet().size == 5) { "Robot faces must map to 5 different cube faces" }
    }

    /** The cube face the robot grips and cannot turn. */
    val gripped: Face = Face.entries.single { f -> robotToCube.values.none { it.first == f } }

    private val cubeToRobot: Map<Face, Pair<Face, Boolean>> =
        robotToCube.entries.associate { (robot, cube) -> cube.first to (robot to cube.second) }

    /** The cube move a robot move produces. */
    fun toCube(robotMove: Move): Move {
        val (face, reversed) = robotToCube.getValue(robotMove.face)
        return Move(face, if (reversed) 4 - robotMove.turns else robotMove.turns)
    }

    /** The robot move that produces [cubeMove], or null for the gripped face. */
    fun toRobot(cubeMove: Move): Move? {
        val (face, reversed) = cubeToRobot[cubeMove.face] ?: return null
        return Move(face, if (reversed) 4 - cubeMove.turns else cubeMove.turns)
    }

    val isIdentity: Boolean get() = robotToCube.all { (robot, cube) -> robot == cube.first && !cube.second }

    /** Plain-language summary, e.g. "robot R turns cube F; holds U". */
    fun describe(): String =
        GanRobotProtocol.ROBOT_FACES.joinToString(", ") { "robot $it = cube ${toCube(Move(it, 1))}" } + "; grips cube $gripped"

    override fun equals(other: Any?) = other is RobotOrientation && other.robotToCube == robotToCube
    override fun hashCode() = robotToCube.hashCode()
    override fun toString() = describe()

    companion object {
        /** Cube placed as assumed: white (U) gripped, green (F) facing the robot's F. */
        val IDENTITY = RobotOrientation(GanRobotProtocol.ROBOT_FACES.associateWith { it to false })

        /**
         * Build from test turns: the cube moves observed when the robot turned
         * its R, F and D faces one quarter turn clockwise. L and B are the faces
         * opposite R and F, turning the same way.
         */
        fun fromTestTurns(robotR: Move, robotF: Move, robotD: Move): RobotOrientation {
            for (m in listOf(robotR, robotF, robotD)) {
                require(m.turns == 1 || m.turns == 3) { "A test turn must show up as a quarter turn, saw $m" }
            }
            require(setOf(robotR.face.axis, robotF.face.axis, robotD.face.axis).size == 3) {
                "Test turns $robotR, $robotF, $robotD are not on three different axes"
            }
            fun entry(m: Move) = m.face to (m.turns == 3)
            fun opposite(m: Move) = m.face.opposite to (m.turns == 3)
            return RobotOrientation(
                mapOf(
                    Face.R to entry(robotR),
                    Face.F to entry(robotF),
                    Face.D to entry(robotD),
                    Face.L to opposite(robotR),
                    Face.B to opposite(robotF),
                ),
            )
        }

        /** For tests and simulations: an arbitrary mapping. */
        fun of(robotToCube: Map<Face, Pair<Face, Boolean>>) = RobotOrientation(robotToCube)
    }
}
