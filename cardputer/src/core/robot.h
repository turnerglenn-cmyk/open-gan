// GAN Robot: wire protocol, how the cube sits in the robot, and robot solves.
//
// Protocol facts documented by rsimp/gan-scrambler; this is an independent
// implementation (that repository has no license, no code was copied).
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <optional>

#include "cube.h"
#include "gan.h"

namespace og {
namespace robot {

constexpr const char* SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb";
constexpr const char* SCRAMBLE_CHAR = "0000fff3-0000-1000-8000-00805f9b34fb";
constexpr const char* STATUS_CHAR = "0000fff2-0000-1000-8000-00805f9b34fb";
constexpr const char* DEVICE_INFO_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb";
constexpr const char* MODEL_NUMBER_CHAR = "00002a24-0000-1000-8000-00805f9b34fb";
constexpr const char* MODEL_NUMBER = "GAN ROBOTCUBE";
constexpr size_t MAX_BYTES_PER_WRITE = 16;

/** Faces the robot can turn, in wire order: R F D L B. */
extern const Face ROBOT_FACES[5];

bool isRobotModel(const std::string& model);
bool canExecute(const Moves& moves);

/** Turns the robot physically makes: doubles become two quarter turns when split. */
Moves executed(const Moves& moves, bool splitDoubles = true);

/** Writes to send: two moves per byte (high nibble first, 0xF pads), max 16 bytes each. */
std::vector<Bytes> encode(const Moves& moves, bool splitDoubles = true);

}  // namespace robot

/**
 * How a smart cube sits in the robot: for each robot face (R F D L B), which cube
 * face turns and whether robot clockwise shows up as counter-clockwise on the cube.
 */
class RobotOrientation {
public:
    struct Entry {
        Face face;
        bool reversed;
        bool operator==(const Entry& o) const { return face == o.face && reversed == o.reversed; }
        bool operator!=(const Entry& o) const { return !(*this == o); }
    };

    static RobotOrientation identity();

    Face gripped() const { return gripped_; }
    Move toCube(const Move& robotMove) const;
    /** The robot move that makes [cubeMove], or nothing for the gripped face. */
    std::optional<Move> toRobot(const Move& cubeMove) const;
    Moves toCube(const Moves& robotMoves) const;
    bool isIdentity() const;
    std::string describe() const;
    bool operator==(const RobotOrientation& o) const;

    /**
     * Pair the quarter turns the robot made with the cube moves the smart cube
     * reported. Nothing unless every turn was reported, the reports agree, and all
     * three axes were seen.
     */
    static std::optional<RobotOrientation> fromObservations(const Moves& robotTurns, const Moves& cubeMoves);

    /** Cube moves from where [reported] left the cube to where [expected] should have. */
    static Moves correction(const Moves& expected, const Moves& reported);

    /** One test turn per axis. */
    static Moves testTurns();

    /** Index 0..5 by Face; only R F D L B are used. */
    static std::optional<RobotOrientation> of(const Entry map[6]);

private:
    RobotOrientation() = default;
    Entry robotToCube_[6]{};
    Face gripped_ = U;
};

namespace robotsolve {

/** Equivalent of [move] that never turns [forbidden]. */
Moves avoiding(const Move& move, Face forbidden);

/** Merge turns of the same face, also across a turn of the opposite face (R L R = R2 L). */
Moves simplify(const Moves& moves);

/** Robot moves (robot face names) that make [cubeMoves], rewriting gripped-face turns. */
Moves forRobot(const Moves& cubeMoves, const RobotOrientation& o);

/** Robot moves that undo [history] (moves since solved, in the cube's frame). */
Moves solutionFor(const Moves& history, const RobotOrientation& o);

}  // namespace robotsolve
}  // namespace og
