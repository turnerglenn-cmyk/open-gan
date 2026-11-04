// SPDX-License-Identifier: GPL-3.0-or-later
#include "robot.h"

#include <algorithm>
#include <cctype>

namespace og {
namespace robot {

const Face ROBOT_FACES[5] = {R, F, D, L, B};

static int robotIndex(Face f) {
    for (int i = 0; i < 5; i++)
        if (ROBOT_FACES[i] == f) return i;
    return -1;
}

bool isRobotModel(const std::string& model) {
    std::string t;
    for (char c : model)
        if (c != '\0') t += char(toupper((unsigned char)c));
    while (!t.empty() && isspace((unsigned char)t.back())) t.pop_back();
    while (!t.empty() && isspace((unsigned char)t.front())) t.erase(t.begin());
    return t == MODEL_NUMBER;
}

bool canExecute(const Moves& moves) {
    return std::none_of(moves.begin(), moves.end(), [](const Move& m) { return m.face == U; });
}

Moves executed(const Moves& moves, bool split) {
    if (!split) return moves;
    Moves out;
    for (const Move& m : moves) {
        if (m.turns == 2) {
            out.emplace_back(m.face, 1);
            out.emplace_back(m.face, 1);
        } else {
            out.push_back(m);
        }
    }
    return out;
}

std::vector<Bytes> encode(const Moves& moves, bool split) {
    std::vector<int> codes;
    for (const Move& m : executed(moves, split)) {
        int idx = robotIndex(m.face);
        if (idx < 0) return {};  // U cannot be turned; callers check canExecute()
        codes.push_back(idx * 3 + (m.turns - 1));
    }
    Bytes bytes;
    for (size_t i = 0; i < codes.size(); i += 2) {
        int lo = i + 1 < codes.size() ? codes[i + 1] : 0xF;
        bytes.push_back(uint8_t((codes[i] << 4) | lo));
    }
    std::vector<Bytes> writes;
    for (size_t i = 0; i < bytes.size(); i += MAX_BYTES_PER_WRITE)
        writes.emplace_back(bytes.begin() + i, bytes.begin() + std::min(bytes.size(), i + MAX_BYTES_PER_WRITE));
    return writes;
}

}  // namespace robot

// ---------------------------------------------------------------- orientation

RobotOrientation RobotOrientation::identity() {
    Entry map[6];
    for (Face f : robot::ROBOT_FACES) map[f] = {f, false};
    return *of(map);
}

std::optional<RobotOrientation> RobotOrientation::of(const Entry map[6]) {
    RobotOrientation o;
    bool used[6] = {};
    for (Face f : robot::ROBOT_FACES) {
        if (used[map[f].face]) return std::nullopt;
        used[map[f].face] = true;
        o.robotToCube_[f] = map[f];
    }
    for (int f = 0; f < 6; f++)
        if (!used[f]) o.gripped_ = Face(f);
    return o;
}

Move RobotOrientation::toCube(const Move& m) const {
    const Entry& e = robotToCube_[m.face];
    return Move(e.face, e.reversed ? 4 - m.turns : m.turns);
}

Moves RobotOrientation::toCube(const Moves& ms) const {
    Moves out;
    for (const Move& m : ms) out.push_back(toCube(m));
    return out;
}

std::optional<Move> RobotOrientation::toRobot(const Move& m) const {
    for (Face f : robot::ROBOT_FACES) {
        const Entry& e = robotToCube_[f];
        if (e.face == m.face) return Move(f, e.reversed ? 4 - m.turns : m.turns);
    }
    return std::nullopt;
}

bool RobotOrientation::isIdentity() const {
    for (Face f : robot::ROBOT_FACES)
        if (robotToCube_[f].face != f || robotToCube_[f].reversed) return false;
    return true;
}

bool RobotOrientation::operator==(const RobotOrientation& o) const {
    for (Face f : robot::ROBOT_FACES)
        if (robotToCube_[f] != o.robotToCube_[f]) return false;
    return true;
}

std::string RobotOrientation::describe() const {
    std::string s;
    for (Face f : robot::ROBOT_FACES) {
        if (!s.empty()) s += ' ';
        s += faceChar(f);
        s += '=';
        s += toCube(Move(f, 1)).str();
    }
    s += " grips ";
    s += faceChar(gripped_);
    return s;
}

Moves RobotOrientation::testTurns() { return {Move(R, 1), Move(F, 1), Move(D, 1)}; }

std::optional<RobotOrientation> RobotOrientation::fromObservations(const Moves& robotTurns, const Moves& cubeMoves) {
    if (robotTurns.size() != cubeMoves.size()) return std::nullopt;
    bool have[6] = {};
    Entry seen[6]{};
    for (size_t i = 0; i < robotTurns.size(); i++) {
        const Move& r = robotTurns[i];
        const Move& c = cubeMoves[i];
        if (r.turns == 2 || c.turns == 2) continue;
        Entry e{c.face, r.turns != c.turns};
        if (have[r.face] && seen[r.face] != e) return std::nullopt;
        have[r.face] = true;
        seen[r.face] = e;
    }
    auto axis = [&](Face a, Face b, Entry& ea, Entry& eb) {
        if (have[a]) {
            ea = seen[a];
            eb = {oppositeOf(ea.face), ea.reversed};
            return true;
        }
        if (have[b]) {
            eb = seen[b];
            ea = {oppositeOf(eb.face), eb.reversed};
            return true;
        }
        return false;
    };
    if (!have[D]) return std::nullopt;
    Entry map[6]{};
    map[D] = seen[D];
    if (!axis(R, L, map[R], map[L])) return std::nullopt;
    if (!axis(F, B, map[F], map[B])) return std::nullopt;
    int ax[3] = {axisOf(map[D].face), axisOf(map[R].face), axisOf(map[F].face)};
    if (ax[0] == ax[1] || ax[1] == ax[2] || ax[0] == ax[2]) return std::nullopt;
    auto o = of(map);
    if (!o) return std::nullopt;
    // Every report must be exactly what this orientation predicts.
    if (o->toCube(robotTurns) != cubeMoves) return std::nullopt;
    return o;
}

Moves RobotOrientation::correction(const Moves& expected, const Moves& reported) {
    size_t k = 0;
    while (k < expected.size() && k < reported.size() && expected[k] == reported[k]) k++;
    Moves out;
    for (size_t i = reported.size(); i > k; i--) out.push_back(reported[i - 1].inverse());
    out.insert(out.end(), expected.begin() + k, expected.end());
    return out;
}

// ---------------------------------------------------------------- solve

namespace robotsolve {

static Moves findSetup(Face target) {
    for (int p = 0; p < 6; p++) {
        if (axisOf(Face(p)) == axisOf(target)) continue;
        for (int q = 0; q < 6; q++) {
            if (axisOf(Face(q)) == axisOf(target) || axisOf(Face(q)) == axisOf(Face(p))) continue;
            for (int pTurns : {1, 3}) {
                Face fp = Face(p), fq = Face(q);
                Moves setup = {Move(fp, pTurns), Move(oppositeOf(fp), 4 - pTurns), Move(fq, 2),
                               Move(oppositeOf(fq), 2), Move(fp, pTurns), Move(oppositeOf(fp), 4 - pTurns)};
                bool works = true;
                for (int t = 1; t <= 3 && works; t++) {
                    Moves seq = setup;
                    seq.emplace_back(oppositeOf(target), t);
                    seq.insert(seq.end(), setup.begin(), setup.end());
                    works = CubieCube::solved().apply(seq) == CubieCube::solved().apply(Move(target, t));
                }
                if (works) return setup;
            }
        }
    }
    return {};
}

static const Moves& setupFor(Face f) {
    static Moves setups[6];
    static bool ready = false;
    if (!ready) {
        for (int i = 0; i < 6; i++) setups[i] = findSetup(Face(i));
        ready = true;
    }
    return setups[f];
}

Moves avoiding(const Move& move, Face forbidden) {
    if (move.face != forbidden) return {move};
    const Moves& s = setupFor(forbidden);
    Moves out = s;
    out.emplace_back(oppositeOf(forbidden), move.turns);
    out.insert(out.end(), s.begin(), s.end());
    return out;
}

Moves simplify(const Moves& moves) {
    std::vector<std::pair<Face, int>> out;
    for (const Move& m : moves) {
        int index = -1;
        size_t n = out.size();
        if (n >= 1 && out[n - 1].first == m.face) index = int(n - 1);
        else if (n >= 2 && axisOf(out[n - 1].first) == axisOf(m.face) && out[n - 2].first == m.face) index = int(n - 2);
        if (index < 0) {
            out.emplace_back(m.face, m.turns);
            continue;
        }
        int turns = (out[index].second + m.turns) % 4;
        if (turns == 0) out.erase(out.begin() + index);
        else out[index].second = turns;
    }
    Moves r;
    for (auto& p : out) r.emplace_back(p.first, p.second);
    return r;
}

Moves forRobot(const Moves& cubeMoves, const RobotOrientation& o) {
    Moves expanded;
    for (const Move& m : simplify(cubeMoves)) {
        Moves a = avoiding(m, o.gripped());
        expanded.insert(expanded.end(), a.begin(), a.end());
    }
    Moves out;
    for (const Move& m : simplify(expanded)) {
        auto r = o.toRobot(m);
        if (r) out.push_back(*r);
    }
    return out;
}

Moves solutionFor(const Moves& history, const RobotOrientation& o) {
    Moves s = simplify(history);
    Moves undo;
    for (size_t i = s.size(); i > 0; i--) undo.push_back(s[i - 1].inverse());
    return forRobot(undo, o);
}

}  // namespace robotsolve
}  // namespace og
