// SPDX-License-Identifier: GPL-3.0-or-later
#include "cube.h"

#include <cctype>

namespace og {

char faceChar(Face f) { return "URFDLB"[f]; }

std::string Move::str() const {
    std::string s(1, faceChar(face));
    if (turns == 2) s += '2';
    else if (turns == 3) s += '\'';
    return s;
}

bool parseMoves(const std::string& text, Moves& out, std::string* error) {
    out.clear();
    size_t i = 0;
    while (i < text.size()) {
        while (i < text.size() && isspace((unsigned char)text[i])) i++;
        if (i >= text.size()) break;
        size_t start = i;
        while (i < text.size() && !isspace((unsigned char)text[i])) i++;
        std::string tok = text.substr(start, i - start);
        const char* faces = "URFDLB";
        int face = -1;
        for (int f = 0; f < 6; f++)
            if (toupper((unsigned char)tok[0]) == faces[f]) face = f;
        int turns = 0;
        std::string suffix = tok.substr(1);
        if (suffix.empty()) turns = 1;
        else if (suffix == "'") turns = 3;
        else if (suffix == "2" || suffix == "2'") turns = 2;
        if (face < 0 || turns == 0) {
            if (error) *error = "Unrecognised move: " + tok;
            out.clear();
            return false;
        }
        out.emplace_back(Face(face), turns);
    }
    return true;
}

Moves parseMovesOrEmpty(const std::string& text) {
    Moves m;
    parseMoves(text, m);
    return m;
}

std::string formatMoves(const Moves& moves) {
    std::string s;
    for (size_t i = 0; i < moves.size(); i++) {
        if (i) s += ' ';
        s += moves[i].str();
    }
    return s;
}

namespace {

CubieCube make(std::array<uint8_t, 8> cp, std::array<uint8_t, 8> co, std::array<uint8_t, 12> ep,
               std::array<uint8_t, 12> eo) {
    CubieCube c;
    c.cp = cp;
    c.co = co;
    c.ep = ep;
    c.eo = eo;
    return c;
}

const CubieCube* basicMoves() {
    static CubieCube moves[6] = {
        make({3, 0, 1, 2, 4, 5, 6, 7}, {}, {3, 0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11}, {}),
        make({4, 1, 2, 0, 7, 5, 6, 3}, {2, 0, 0, 1, 1, 0, 0, 2}, {8, 1, 2, 3, 11, 5, 6, 7, 4, 9, 10, 0}, {}),
        make({1, 5, 2, 3, 0, 4, 6, 7}, {1, 2, 0, 0, 2, 1, 0, 0}, {0, 9, 2, 3, 4, 8, 6, 7, 1, 5, 10, 11},
             {0, 1, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0}),
        make({0, 1, 2, 3, 5, 6, 7, 4}, {}, {0, 1, 2, 3, 5, 6, 7, 4, 8, 9, 10, 11}, {}),
        make({0, 2, 6, 3, 4, 1, 5, 7}, {0, 1, 2, 0, 0, 2, 1, 0}, {0, 1, 10, 3, 4, 5, 9, 7, 8, 2, 6, 11}, {}),
        make({0, 1, 3, 7, 4, 5, 2, 6}, {0, 0, 1, 2, 0, 0, 2, 1}, {0, 1, 2, 11, 4, 5, 6, 10, 8, 9, 3, 7},
             {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 1}),
    };
    return moves;
}

const uint8_t CORNER_FACELETS[8][3] = {{8, 9, 20},   {6, 18, 38},  {0, 36, 47},  {2, 45, 11},
                                       {29, 26, 15}, {27, 44, 24}, {33, 53, 42}, {35, 17, 51}};
const uint8_t EDGE_FACELETS[12][2] = {{5, 10},  {7, 19},  {3, 37},  {1, 46},  {32, 16}, {28, 25},
                                      {30, 43}, {34, 52}, {23, 12}, {21, 41}, {50, 39}, {48, 14}};

}  // namespace

const CubieCube& CubieCube::solved() {
    static CubieCube s;
    return s;
}

CubieCube CubieCube::operator*(const CubieCube& o) const {
    CubieCube r;
    for (int i = 0; i < 8; i++) {
        r.cp[i] = cp[o.cp[i]];
        r.co[i] = (co[o.cp[i]] + o.co[i]) % 3;
    }
    for (int i = 0; i < 12; i++) {
        r.ep[i] = ep[o.ep[i]];
        r.eo[i] = (eo[o.ep[i]] + o.eo[i]) % 2;
    }
    return r;
}

CubieCube CubieCube::apply(const Move& m) const {
    CubieCube c = *this;
    for (int i = 0; i < m.turns; i++) c = c * basicMoves()[m.face];
    return c;
}

CubieCube CubieCube::apply(const Moves& ms) const {
    CubieCube c = *this;
    for (const Move& m : ms) c = c.apply(m);
    return c;
}

std::string CubieCube::toFacelets() const {
    const char* faces = "URFDLB";
    std::string f(54, 'U');
    for (int i = 0; i < 54; i++) f[i] = faces[i / 9];
    for (int i = 0; i < 8; i++)
        for (int p = 0; p < 3; p++) f[CORNER_FACELETS[i][(p + co[i]) % 3]] = faces[CORNER_FACELETS[cp[i]][p] / 9];
    for (int i = 0; i < 12; i++)
        for (int p = 0; p < 2; p++) f[EDGE_FACELETS[i][(p + eo[i]) % 2]] = faces[EDGE_FACELETS[ep[i]][p] / 9];
    return f;
}

}  // namespace og
