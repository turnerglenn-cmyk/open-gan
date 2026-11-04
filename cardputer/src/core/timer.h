// Scrambler, smart cube timer state machine, timestamp fit and stats.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "cube.h"
#include "gan.h"

namespace og {

/** Random-move scrambles. [robotSafe] never turns U and opens with one turn per axis. */
class Scrambler {
public:
    explicit Scrambler(std::function<uint32_t()> rng) : rng_(std::move(rng)) {}
    Moves randomMoves(int length = 25, bool robotSafe = true);

private:
    std::function<uint32_t()> rng_;
};

struct TimedMove {
    Move move;
    int64_t localTs;  // NO_TS if unknown
    int64_t cubeTs;   // NO_TS if unknown
};

/**
 * Fits cube hardware timestamps onto the host clock (linear regression), filling
 * gaps for recovered moves. Port of gan-web-bluetooth's cubeTimestampLinearFit (MIT).
 * Returns moves with cubeTs rebased so the first move is at 0.
 */
std::vector<TimedMove> fitTimestamps(const std::vector<TimedMove>& input);

struct SolveResult {
    int64_t timeMs = 0;
    std::string scramble;
    std::string solution;
    int moveCount = 0;
    double tps() const { return timeMs <= 0 ? 0 : std::max(moveCount - 1, 0) * 1000.0 / double(timeMs); }
};

/**
 * Scramble set -> cube tracked toward the scramble state -> Ready -> first turn
 * starts -> solved stops. Feed it from one thread.
 */
class SmartTimer {
public:
    enum class Phase { IDLE, SCRAMBLING, READY, RUNNING, FINISHED };

    std::function<void(Phase)> listener;

    const CubieCube& cube() const { return cube_; }
    Phase phase() const { return phase_; }
    int applied() const { return applied_; }
    int64_t startedAt() const { return startedAt_; }
    int moveCount() const { return int(solveMoves_.size()); }
    const SolveResult& result() const { return result_; }

    void setScramble(const Moves& scramble);
    void armFree();
    void reset();
    void onFacelets(const CubieCube& state);
    void onMove(const Move& move, int64_t localTs, int64_t cubeTs);

private:
    void setPhase(Phase p);
    void checkScrambled();
    void finish();

    CubieCube cube_;
    Phase phase_ = Phase::IDLE;
    Moves scramble_;
    bool hasScramble_ = false;
    std::vector<CubieCube> states_;
    int applied_ = 0;
    int64_t startedAt_ = 0;
    std::vector<TimedMove> solveMoves_;
    SolveResult result_;
};

enum class Penalty : uint8_t { NONE = 0, PLUS_TWO = 1, DNF = 2 };

struct Solve {
    uint32_t timeMs = 0;
    Penalty penalty = Penalty::NONE;
    /** Effective time, or -1 for DNF. */
    int64_t effective() const {
        return penalty == Penalty::DNF ? -1 : int64_t(timeMs) + (penalty == Penalty::PLUS_TWO ? 2000 : 0);
    }
};

namespace stats {
/** WCA-style trimmed average of the last n; -1 if DNF, -2 if too few solves. */
int64_t averageOf(const std::vector<Solve>& solves, int n);
int64_t mean(const std::vector<Solve>& solves);
int64_t best(const std::vector<Solve>& solves);
/** "12.34", "1:02.50", or "DNF" for -1, "-" for -2. */
std::string format(int64_t ms);
}  // namespace stats

}  // namespace og
