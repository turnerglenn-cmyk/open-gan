// Host-side unit tests for the Cardputer core (ported from the Kotlin core tests).
// Build and run: make -C cardputer/test/host
// SPDX-License-Identifier: GPL-3.0-or-later
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <set>

#include "../../src/core/cube.h"
#include "../../src/core/gan.h"
#include "../../src/core/robot.h"
#include "../../src/core/timer.h"

using namespace og;

static int failures = 0, checks = 0;
#define CHECK(cond)                                                       \
    do {                                                                  \
        checks++;                                                         \
        if (!(cond)) {                                                    \
            failures++;                                                   \
            fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
        }                                                                 \
    } while (0)
#define CHECK_EQ(a, b) CHECK((a) == (b))

static Moves mv(const char* s) { return parseMovesOrEmpty(s); }

struct BitWriter {
    Bytes bytes;
    int pos = 0;
    explicit BitWriter(size_t n) : bytes(n, 0) {}
    BitWriter& put(int64_t v, int bits) {
        for (int i = bits - 1; i >= 0; i--) {
            if ((v >> i) & 1) bytes[pos / 8] |= uint8_t(0x80 >> (pos % 8));
            pos++;
        }
        return *this;
    }
    BitWriter& le16(int v) { return put(v & 0xFF, 8).put((v >> 8) & 0xFF, 8); }
    BitWriter& le32(int64_t v) {
        for (int i = 0; i < 4; i++) put((v >> (8 * i)) & 0xFF, 8);
        return *this;
    }
    BitWriter& state(const CubieCube& c, int gap = 0) {
        for (int i = 0; i < 7; i++) put(c.cp[i], 3);
        for (int i = 0; i < 7; i++) put(c.co[i], 2);
        put(0, gap);
        for (int i = 0; i < 11; i++) put(c.ep[i], 4);
        for (int i = 0; i < 11; i++) put(c.eo[i], 1);
        return *this;
    }
};

struct FakeLink : CubeLink {
    std::vector<Bytes> sent;
    bool disconnected = false;
    void sendCommand(const Bytes& m) override { sent.push_back(m); }
    void disconnect() override { disconnected = true; }
};

static void testCube() {
    CHECK_EQ(CubieCube::solved().toFacelets(), std::string("UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB"));
    CubieCube c = CubieCube::solved().apply(mv("F R"));
    CHECK((c.cp == std::array<uint8_t, 8>{{0, 5, 2, 1, 7, 4, 6, 3}}));
    CHECK((c.co == std::array<uint8_t, 8>{{1, 2, 0, 2, 1, 1, 0, 2}}));
    CHECK_EQ(c.toFacelets(), std::string("UUFUUFLLFUUURRRRRRFFRFFDFFDRRBDDBDDBLLDLLDLLDLBBUBBUBB"));
    for (int f = 0; f < 6; f++) {
        Move m(Face(f), 1);
        CHECK(!CubieCube::solved().apply(m).isSolved());
        CHECK(CubieCube::solved().apply(Moves{m, m, m, m}).isSolved());
    }
    CubieCube s;
    for (int i = 0; i < 6; i++) s = s.apply(mv("R U R' U'"));
    CHECK(s.isSolved());
    CHECK_EQ(formatMoves(mv("  R U'  F2 D ")), std::string("R U' F2 D"));
    CHECK(mv("B2'")[0] == Move(B, 2));
    Moves bad;
    CHECK(!parseMoves("R X", bad));
}

static void testAes() {
    // FIPS-197 appendix C.1
    uint8_t key[16], block[16];
    for (int i = 0; i < 16; i++) key[i] = uint8_t(i), block[i] = uint8_t(i * 0x11);
    Aes128 aes(key);
    aes.encryptBlock(block);
    const uint8_t expected[16] = {0x69, 0xc4, 0xe0, 0xd8, 0x6a, 0x7b, 0x04, 0x30,
                                  0xd8, 0xcd, 0xb7, 0x80, 0x70, 0xb4, 0xc5, 0x5a};
    CHECK(memcmp(block, expected, 16) == 0);
    aes.decryptBlock(block);
    for (int i = 0; i < 16; i++) CHECK_EQ(block[i], uint8_t(i * 0x11));
}

static void testEncryption() {
    uint8_t salt[6];
    CHECK(gan::saltFromMac("AB:12:CD:34:EF:56", salt));
    CHECK_EQ(salt[0], 0x56);
    CHECK_EQ(salt[5], 0xAB);
    GanEncrypter enc(gan::KEY_GAN, salt);
    Bytes plain(20);
    for (int i = 0; i < 20; i++) plain[i] = uint8_t(i * 7);
    Bytes cipher = enc.encrypt(plain);
    CHECK(cipher != plain);
    CHECK(enc.decrypt(cipher) == plain);

    const uint8_t data[] = {0, 0, 0, 0x56, 0xEF, 0x34, 0xCD, 0x12, 0xAB, 0x77};
    CHECK_EQ(gan::macFromManufacturerData(data, sizeof data), std::string("AB:12:CD:34:EF:56"));
}

static const CubieCube scrambled = CubieCube::solved().apply(parseMovesOrEmpty("F R U' D2 B L'"));

static void testGen2() {
    Gen2Driver d;
    FakeLink link;
    CubeEvents ev;
    d.handle(link, BitWriter(20).put(0x04, 4).put(10, 8).state(scrambled).bytes, 1000, ev);
    CHECK_EQ(ev.size(), 1u);
    CHECK(ev[0].type == CubeEvent::FACELETS && ev[0].state == scrambled);

    BitWriter w(20);
    w.put(0x02, 4).put(12, 8);
    w.put(1, 4).put(1, 1);  // i=0: R'
    w.put(0, 4).put(0, 1);  // i=1: U
    for (int i = 0; i < 5; i++) w.put(0, 5);
    w.put(120, 16).put(80, 16);
    ev.clear();
    d.handle(link, w.bytes, 2000, ev);
    CHECK_EQ(ev.size(), 2u);
    CHECK_EQ(ev[0].move.str(), std::string("U"));
    CHECK_EQ(ev[1].move.str(), std::string("R'"));
    CHECK_EQ(ev[0].serial, 11);
    CHECK_EQ(ev[1].serial, 12);
    CHECK_EQ(ev[0].cubeTs, 80);
    CHECK_EQ(ev[1].cubeTs, 200);
    CHECK_EQ(ev[0].localTs, NO_TS);
    CHECK_EQ(ev[1].localTs, 2000);
}

static Bytes gen4Move(int serial, int faceCode, int dir, int64_t ts) {
    return BitWriter(20).put(0x01, 8).put(9, 8).le32(ts).le16(serial).put(dir, 2).put(faceCode, 6).bytes;
}

static void testGen4History() {
    Gen4Driver d;
    FakeLink link;
    CubeEvents ev;
    d.handle(link, BitWriter(20).put(0xED, 8).put(18, 8).le16(5).state(scrambled, 2).bytes, 0, ev);
    CHECK(ev.size() == 1 && ev[0].state == scrambled);
    ev.clear();
    d.handle(link, gen4Move(6, 32, 0, 100), 100, ev);
    CHECK_EQ(ev.size(), 1u);
    ev.clear();
    d.handle(link, gen4Move(8, 8, 0, 300), 300, ev);
    CHECK(ev.empty());
    CHECK_EQ(link.sent.size(), 1u);
    CHECK_EQ(link.sent[0][0], 0xD1);
    Bytes hist = BitWriter(20).put(0xD1, 8).put(2, 8).put(8, 8).put(3, 3).put(0, 1).put(0, 3).put(1, 1).bytes;
    ev.clear();
    d.handle(link, hist, 400, ev);
    CHECK_EQ(ev.size(), 2u);
    if (ev.size() == 2) {
        CHECK_EQ(ev[0].serial, 7);
        CHECK_EQ(ev[1].serial, 8);
        CHECK_EQ(ev[0].move.str(), std::string("D'"));
        CHECK_EQ(ev[1].move.str(), std::string("F"));
    }
}

static void testGen3() {
    Gen3Driver d;
    FakeLink link;
    CubeEvents ev;
    d.handle(link, BitWriter(16).put(0x55, 8).put(0x10, 8).put(1, 8).put(87, 8).bytes, 0, ev);
    CHECK(ev.size() == 1 && ev[0].type == CubeEvent::BATTERY && ev[0].battery == 87);
    ev.clear();
    d.handle(link, BitWriter(20).put(0x55, 8).put(0x02, 8).put(10, 8).le16(1).state(scrambled, 2).bytes, 0, ev);
    CHECK(ev.size() == 1 && ev[0].state == scrambled);
    ev.clear();
    d.handle(link, BitWriter(16).put(0x55, 8).put(0x01, 8).put(7, 8).le32(5000).le16(2).put(1, 2).put(4, 6).bytes, 10, ev);
    CHECK(ev.size() == 1 && ev[0].move.str() == "B'" && ev[0].cubeTs == 5000);
}

static void testTimer() {
    std::vector<TimedMove> moves = {
        {Move(R, 1), 1000, 0}, {Move(U, 1), 1290, 250}, {Move(R, 3), NO_TS, NO_TS}, {Move(U, 3), 1540, 520}};
    auto fitted = fitTimestamps(moves);
    CHECK_EQ(fitted.front().cubeTs, 0);
    for (size_t i = 1; i < fitted.size(); i++) CHECK(fitted[i].cubeTs >= fitted[i - 1].cubeTs);
    int64_t time = fitted.back().cubeTs - fitted.front().cubeTs;
    CHECK(time >= 500 && time <= 560);

    SmartTimer t;
    t.setScramble(mv("R F"));
    CHECK(t.phase() == SmartTimer::Phase::SCRAMBLING);
    t.onMove(Move(R, 1), 0, 0);
    CHECK_EQ(t.applied(), 1);
    t.onMove(Move(F, 1), 10, 10);
    CHECK(t.phase() == SmartTimer::Phase::READY);
    t.onMove(Move(F, 3), 1000, 1000);
    CHECK(t.phase() == SmartTimer::Phase::RUNNING);
    t.onMove(Move(R, 3), 2500, 2500);
    CHECK(t.phase() == SmartTimer::Phase::FINISHED);
    CHECK_EQ(t.result().timeMs, 1500);
    CHECK_EQ(t.result().solution, std::string("F' R'"));
    CHECK_EQ(t.result().scramble, std::string("R F"));

    std::vector<Solve> s;
    for (uint32_t ms : {10000u, 12000u, 11000u, 9000u, 30000u}) s.push_back({ms, Penalty::NONE});
    CHECK_EQ(stats::averageOf(s, 5), 11000);
    CHECK_EQ(stats::averageOf(s, 12), -2);
    CHECK_EQ(stats::best(s), 9000);
    s[0].penalty = Penalty::DNF;
    s[1].penalty = Penalty::DNF;
    CHECK_EQ(stats::averageOf(s, 5), -1);
    CHECK_EQ(stats::format(62500), std::string("1:02.50"));
    CHECK_EQ(stats::format(9876), std::string("9.87"));
}

static void testRobotProtocol() {
    auto w = robot::encode(mv("R F' D"));
    CHECK(w.size() == 1 && w[0] == (Bytes{0x05, 0x6F}));
    CHECK(robot::encode(mv("L2 B'"))[0] == (Bytes{0x99, 0xEF}));
    CHECK(robot::encode(mv("L2 B'"), false)[0] == (Bytes{0xAE}));
    Moves forty(40, Move(R, 1));
    auto chunks = robot::encode(forty);
    CHECK(chunks.size() == 2 && chunks[0].size() == 16 && chunks[1].size() == 4);
    CHECK(robot::encode(mv("R U")).empty());
    CHECK(robot::isRobotModel("GAN ROBOTCUBE\0"));

    std::mt19937 rng(42);
    Scrambler sc([&] { return uint32_t(rng()); });
    for (int k = 0; k < 200; k++) {
        Moves m = sc.randomMoves(25, true);
        CHECK_EQ(m.size(), 25u);
        CHECK(robot::canExecute(m));
        for (size_t i = 1; i < m.size(); i++) {
            CHECK(m[i].face != m[i - 1].face);
            if (i >= 2 && axisOf(m[i].face) == axisOf(m[i - 1].face)) CHECK(m[i].face != m[i - 2].face);
        }
        CHECK(axisOf(m[0].face) != axisOf(m[1].face) && axisOf(m[1].face) != axisOf(m[2].face) &&
              axisOf(m[0].face) != axisOf(m[2].face));
    }
}

/** All 48 robot-to-cube mappings from the 24 rotations x reversed or not. */
static std::vector<RobotOrientation> allOrientations() {
    const int vec[6][3] = {{0, 1, 0}, {1, 0, 0}, {0, 0, 1}, {0, -1, 0}, {-1, 0, 0}, {0, 0, -1}};  // U R F D L B
    auto faceOf = [&](const int v[3]) {
        for (int f = 0; f < 6; f++)
            if (vec[f][0] == v[0] && vec[f][1] == v[1] && vec[f][2] == v[2]) return Face(f);
        return U;
    };
    const int perms[6][3] = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
    std::vector<RobotOrientation> out;
    std::set<std::string> seen;
    for (auto& p : perms)
        for (int signs = 0; signs < 8; signs++) {
            int m[3][3] = {};
            for (int i = 0; i < 3; i++) m[i][p[i]] = ((signs >> i) & 1) ? -1 : 1;
            int det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                      m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
            if (det != 1) continue;
            for (bool rev : {false, true}) {
                RobotOrientation::Entry map[6]{};
                for (int f = 0; f < 6; f++) {
                    int v[3];
                    for (int i = 0; i < 3; i++) v[i] = m[i][0] * vec[f][0] + m[i][1] * vec[f][1] + m[i][2] * vec[f][2];
                    map[f] = {faceOf(v), rev};
                }
                auto o = RobotOrientation::of(map);
                CHECK(o.has_value());
                if (o && seen.insert(o->describe()).second) out.push_back(*o);
            }
        }
    return out;
}

static void testRobotSolve() {
    for (int f = 0; f < 6; f++)
        for (int t = 1; t <= 3; t++) {
            Move m(Face(f), t);
            Moves alt = robotsolve::avoiding(m, Face(f));
            for (const Move& a : alt) CHECK(a.face != f);
            CHECK(CubieCube::solved().apply(m) == CubieCube::solved().apply(alt));
        }
    CHECK_EQ(formatMoves(robotsolve::solutionFor(mv("R F' D2 L B R2"), RobotOrientation::identity())),
             std::string("R2 B' L' D2 F R'"));
    CHECK_EQ(formatMoves(robotsolve::simplify(mv("R L R"))), std::string("R2 L"));
    CHECK_EQ(formatMoves(robotsolve::simplify(mv("F R R' F'"))), std::string(""));
    CHECK_EQ(formatMoves(robotsolve::simplify(mv("D2 D"))), std::string("D'"));

    auto orients = allOrientations();
    CHECK_EQ(orients.size(), 48u);
    std::mt19937 rng(3);
    Scrambler sc([&] { return uint32_t(rng()); });
    for (const auto& o : orients) {
        for (int k = 0; k < 10; k++) {
            Moves history = sc.randomMoves(25, false);
            Moves robotMoves = robotsolve::solutionFor(history, o);
            CHECK(robot::canExecute(robotMoves));
            CHECK(CubieCube::solved().apply(history).apply(o.toCube(robotMoves)).isSolved());
        }
        // The test turns reveal the placement, and a missed turn is not mistaken for one.
        Moves test = RobotOrientation::testTurns();
        for (int i = 2; i >= 0; i--) test.push_back(RobotOrientation::testTurns()[i].inverse());
        Moves reports = o.toCube(test);
        auto found = RobotOrientation::fromObservations(test, reports);
        CHECK(found && *found == o);
        Moves missing = reports;
        missing.erase(missing.begin() + 1);
        CHECK(!RobotOrientation::fromObservations(test, missing));
    }
    CHECK(!RobotOrientation::fromObservations(mv("R F"), mv("R F")));

    for (int k = 0; k < 50; k++) {
        Moves expected = robot::executed(sc.randomMoves(25, true));
        Moves reported = expected;
        reported.erase(reported.begin() + (rng() % reported.size()));
        Moves fix = RobotOrientation::correction(expected, reported);
        Moves all = reported;
        all.insert(all.end(), fix.begin(), fix.end());
        CHECK(CubieCube::solved().apply(expected) == CubieCube::solved().apply(all));
    }
}

int main() {
    testCube();
    testAes();
    testEncryption();
    testGen2();
    testGen3();
    testGen4History();
    testTimer();
    testRobotProtocol();
    testRobotSolve();
    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
