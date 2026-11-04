// Open GAN for Cardputer - cube model.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace og {

enum Face : uint8_t { U = 0, R = 1, F = 2, D = 3, L = 4, B = 5 };

inline int axisOf(Face f) { return f % 3; }
inline Face oppositeOf(Face f) { return Face((f + 3) % 6); }
char faceChar(Face f);

/** A face turn: turns = clockwise quarter turns, 1, 2 or 3 (= prime). */
struct Move {
    Face face = U;
    uint8_t turns = 1;
    Move() = default;
    Move(Face f, int t) : face(f), turns(uint8_t(t)) {}
    Move inverse() const { return Move(face, 4 - turns); }
    std::string str() const;
    bool operator==(const Move& o) const { return face == o.face && turns == o.turns; }
    bool operator!=(const Move& o) const { return !(*this == o); }
};

using Moves = std::vector<Move>;

/** Parse "R U2 F'" style text. Returns false (and leaves error set) on a bad token. */
bool parseMoves(const std::string& text, Moves& out, std::string* error = nullptr);
Moves parseMovesOrEmpty(const std::string& text);
std::string formatMoves(const Moves& moves);

/**
 * 3x3 cube at the cubie level, Kociemba conventions:
 * Corners URF UFL ULB UBR DFR DLF DBL DRB, edges UR UF UL UB DR DF DL DB FR FL BL BR.
 * GAN smart cubes report their state in exactly this layout.
 */
struct CubieCube {
    std::array<uint8_t, 8> cp{{0, 1, 2, 3, 4, 5, 6, 7}};
    std::array<uint8_t, 8> co{};
    std::array<uint8_t, 12> ep{{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}};
    std::array<uint8_t, 12> eo{};

    static const CubieCube& solved();
    bool isSolved() const { return *this == solved(); }

    /** Apply [other] after this. */
    CubieCube operator*(const CubieCube& other) const;
    CubieCube apply(const Move& m) const;
    CubieCube apply(const Moves& ms) const;

    /** Kociemba facelet string, URFDLB order, 54 chars. */
    std::string toFacelets() const;

    bool operator==(const CubieCube& o) const { return cp == o.cp && co == o.co && ep == o.ep && eo == o.eo; }
    bool operator!=(const CubieCube& o) const { return !(*this == o); }
};

}  // namespace og
