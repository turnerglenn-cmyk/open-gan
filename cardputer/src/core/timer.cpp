// SPDX-License-Identifier: GPL-3.0-or-later
#include "timer.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

namespace og {

// ---------------------------------------------------------------- scrambler

Moves Scrambler::randomMoves(int length, bool robotSafe) {
    Moves out;
    while (int(out.size()) < length) {
        Face face = robotSafe ? Face(1 + rng_() % 5) : Face(rng_() % 6);
        size_t n = out.size();
        if (n >= 1 && out[n - 1].face == face) continue;
        // Robot scrambles open with one turn per axis, so a smart cube's reports of
        // the first moves alone show how it sits in the robot.
        if (robotSafe && n < 3 && std::any_of(out.begin(), out.end(), [&](const Move& m) { return axisOf(m.face) == axisOf(face); }))
            continue;
        // No X Y X on one axis (R L R), which would partly cancel.
        if (n >= 2 && axisOf(out[n - 1].face) == axisOf(face) && out[n - 2].face == face) continue;
        out.emplace_back(face, 1 + int(rng_() % 3));
    }
    return out;
}

// ---------------------------------------------------------------- timestamp fit

std::vector<TimedMove> fitTimestamps(const std::vector<TimedMove>& input) {
    std::vector<TimedMove> m = input;
    if (m.empty()) return m;
    for (size_t i = m.size() - 1; i >= 1; i--)
        if (m[i].cubeTs != NO_TS && m[i - 1].cubeTs == NO_TS) m[i - 1].cubeTs = m[i].cubeTs - 50;
    for (size_t i = 0; i + 1 < m.size(); i++)
        if (m[i].cubeTs != NO_TS && m[i + 1].cubeTs == NO_TS) m[i + 1].cubeTs = m[i].cubeTs + 50;

    double sx = 0, sy = 0, sxy = 0, sxx = 0;
    int n = 0;
    for (const TimedMove& t : m) {
        if (t.cubeTs == NO_TS || t.localTs == NO_TS) continue;
        double x = double(t.cubeTs), y = double(t.localTs);
        n++;
        sx += x;
        sy += y;
        sxy += x * y;
        sxx += x * x;
    }
    double varX = n * sxx - sx * sx;
    double covXY = n * sxy - sx * sy;
    double slope = varX < 1e-3 ? 1.0 : covXY / varX;
    double intercept = n < 1 ? 0.0 : sy / n - slope * sx / n;

    int64_t base = m[0].cubeTs == NO_TS ? 0 : m[0].cubeTs;
    int64_t first = llround(slope * double(base) + intercept);
    for (TimedMove& t : m) {
        int64_t ts = t.cubeTs == NO_TS ? base : t.cubeTs;
        t.cubeTs = llround(slope * double(ts) + intercept) - first;
    }
    return m;
}

// ---------------------------------------------------------------- smart timer

void SmartTimer::setPhase(Phase p) {
    phase_ = p;
    if (listener) listener(p);
}

void SmartTimer::setScramble(const Moves& scramble) {
    scramble_ = scramble;
    hasScramble_ = true;
    states_.clear();
    CubieCube c;
    states_.push_back(c);
    for (const Move& m : scramble) states_.push_back(c = c.apply(m));
    applied_ = 0;
    for (int i = int(states_.size()) - 1; i >= 0; i--)
        if (states_[i] == cube_) {
            applied_ = i;
            break;
        }
    setPhase(Phase::SCRAMBLING);
    checkScrambled();
}

void SmartTimer::armFree() {
    states_.clear();
    hasScramble_ = false;
    setPhase(Phase::READY);
}

void SmartTimer::reset() {
    states_.clear();
    solveMoves_.clear();
    setPhase(Phase::IDLE);
}

void SmartTimer::onFacelets(const CubieCube& state) {
    if (state == cube_) return;
    cube_ = state;
    if (phase_ == Phase::SCRAMBLING) checkScrambled();
}

void SmartTimer::onMove(const Move& move, int64_t localTs, int64_t cubeTs) {
    cube_ = cube_.apply(move);
    switch (phase_) {
        case Phase::SCRAMBLING: checkScrambled(); break;
        case Phase::READY:
            solveMoves_.clear();
            solveMoves_.push_back({move, localTs, cubeTs});
            startedAt_ = localTs == NO_TS ? 0 : localTs;
            setPhase(Phase::RUNNING);
            if (cube_.isSolved()) finish();
            break;
        case Phase::RUNNING:
            solveMoves_.push_back({move, localTs, cubeTs});
            if (cube_.isSolved()) finish();
            else setPhase(Phase::RUNNING);
            break;
        default: break;
    }
}

void SmartTimer::checkScrambled() {
    if (phase_ != Phase::SCRAMBLING || states_.empty()) return;
    if (cube_ == states_.back()) {
        setPhase(Phase::READY);
        return;
    }
    for (int i = int(states_.size()) - 1; i >= 0; i--) {
        if (states_[i] == cube_) {
            if (i != applied_) {
                applied_ = i;
                setPhase(Phase::SCRAMBLING);
            }
            return;
        }
    }
}

void SmartTimer::finish() {
    std::vector<TimedMove> fitted = fitTimestamps(solveMoves_);
    result_ = SolveResult();
    result_.timeMs = fitted.back().cubeTs - fitted.front().cubeTs;
    if (hasScramble_) result_.scramble = formatMoves(scramble_);
    Moves sol;
    for (const TimedMove& t : fitted) sol.push_back(t.move);
    result_.solution = formatMoves(sol);
    result_.moveCount = int(sol.size());
    setPhase(Phase::FINISHED);
}

// ---------------------------------------------------------------- stats

namespace stats {

int64_t averageOf(const std::vector<Solve>& solves, int n) {
    if (int(solves.size()) < n || n < 3) return -2;
    std::vector<int64_t> w;
    for (size_t i = solves.size() - n; i < solves.size(); i++) {
        int64_t e = solves[i].effective();
        w.push_back(e < 0 ? INT64_MAX : e);
    }
    std::sort(w.begin(), w.end());
    int trim = std::max(1, int(std::ceil(n * 0.05)));
    int64_t sum = 0;
    for (int i = trim; i < n - trim; i++) {
        if (w[i] == INT64_MAX) return -1;
        sum += w[i];
    }
    return sum / (n - 2 * trim);
}

int64_t mean(const std::vector<Solve>& solves) {
    int64_t sum = 0;
    int n = 0;
    for (const Solve& s : solves)
        if (s.effective() >= 0) {
            sum += s.effective();
            n++;
        }
    return n ? sum / n : -2;
}

int64_t best(const std::vector<Solve>& solves) {
    int64_t b = -2;
    for (const Solve& s : solves)
        if (s.effective() >= 0 && (b < 0 || s.effective() < b)) b = s.effective();
    return b;
}

std::string format(int64_t ms) {
    if (ms == -1) return "DNF";
    if (ms < 0) return "-";
    char buf[24];
    long long minutes = ms / 60000, seconds = (ms % 60000) / 1000, hundredths = (ms % 1000) / 10;
    if (minutes > 0) snprintf(buf, sizeof buf, "%lld:%02lld.%02lld", minutes, seconds, hundredths);
    else snprintf(buf, sizeof buf, "%lld.%02lld", seconds, hundredths);
    return buf;
}

}  // namespace stats
}  // namespace og
