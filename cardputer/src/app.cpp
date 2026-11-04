// SPDX-License-Identifier: GPL-3.0-or-later
#include "app.h"

#include <Arduino.h>
#include <M5Cardputer.h>
#include <Preferences.h>
#include <esp_random.h>

#include <algorithm>

namespace og {

App app;

namespace {
Preferences prefs;
constexpr int MAX_ATTEMPTS = 3;
constexpr size_t MAX_SOLVES = 1000;
/** A turn and back: enough to wake a sleeping smart cube without changing it. */
const Moves WAKE_TURNS = {Move(R, 1), Move(R, 3)};

using Lock = std::lock_guard<std::recursive_mutex>;

struct StoredSolve {
    uint32_t timeMs;
    uint8_t penalty;
} __attribute__((packed));

std::string addrKey(const NimBLEAddress& a) {
    return ble::addressString(a) + "|" + std::to_string(int(a.getType()));
}

std::string remembered(const char* key) {
    std::lock_guard<std::recursive_mutex> g(app.mx);
    return prefs.getString(key, "").c_str();
}

bool parseAddrKey(const std::string& k, NimBLEAddress& out) {
    size_t bar = k.find('|');
    if (bar == std::string::npos) return false;
    out = NimBLEAddress(k.substr(0, bar), uint8_t(atoi(k.c_str() + bar + 1)));
    return true;
}
}  // namespace

// ---------------------------------------------------------------- setup and loop

void App::begin() {
    scrambler_.reset(new Scrambler([] { return esp_random(); }));
    s.timer.listener = [this](SmartTimer::Phase p) { onPhase(p); };
    prefs.begin("opengan", false);
    s.sound = prefs.getBool("sound", true);
    load();
    newScramble();
    TaskHandle_t h;
    xTaskCreatePinnedToCore(workerMain, "og-worker", 12288, this, 1, &h, 0);
    worker_ = h;
}

void App::loop() {
    Lock g(mx);
    if (cube_) {
        CubeEvents events;
        cube_->poll(events);
        for (const CubeEvent& e : events) onCubeEvent(e);
        if (!cube_->connected()) {
            cube_.reset();
            s.cubeConnected = false;
            s.cubeInfo = CubeInfo();
            s.orientation.reset();
            say("Cube disconnected");
        }
    }
    if (robot_ && !jobRunning_ && !robot_->connected()) {
        robot_.reset();
        s.robotConnected = false;
        s.orientation.reset();
        say("Robot disconnected");
    }
}

void App::say(const std::string& m) {
    Lock g(mx);
    s.message = m;
    s.messageAt = millis();
}

void App::beep(int freq, int ms) {
    if (s.sound) M5Cardputer.Speaker.tone(freq, ms);
}

// ---------------------------------------------------------------- cube events and timer

void App::onCubeEvent(const CubeEvent& e) {
    switch (e.type) {
        case CubeEvent::MOVE:
            // After a solve the result stays on screen until the cube is turned again.
            if (s.timer.phase() == SmartTimer::Phase::FINISHED) s.timer.setScramble(s.scramble);
            s.timer.onMove(e.move, e.localTs, e.cubeTs);
            if (recording_) recording_->push_back(e.move);
            if (historyKnown_) history_.push_back(e.move);
            if (s.timer.cube().isSolved()) {
                history_.clear();
                historyKnown_ = true;
            }
            break;
        case CubeEvent::FACELETS: {
            CubieCube tracked = s.timer.cube();
            s.timer.onFacelets(e.state);
            if (e.state.isSolved()) {
                history_.clear();
                historyKnown_ = true;
            } else if (e.state != tracked) {
                history_.clear();
                historyKnown_ = false;
            }
            break;
        }
        case CubeEvent::BATTERY: s.cubeInfo.battery = e.battery; break;
        case CubeEvent::HARDWARE: s.cubeInfo.hardware = e.hwName; break;
    }
    updateCanSolve();
}

void App::onPhase(SmartTimer::Phase p) {
    if (p == SmartTimer::Phase::READY) beep(1760, 60);
    if (p != SmartTimer::Phase::FINISHED) return;
    save(uint32_t(std::max<int64_t>(0, s.timer.result().timeMs)));
    newScrambleKeepingTimer();
}

void App::updateCanSolve() { s.canSolve = historyKnown_ && !history_.empty(); }

void App::newScrambleKeepingTimer() {
    s.scramble = scrambler_->randomMoves(25, robot_ != nullptr);
}

void App::newScramble() {
    Lock g(mx);
    if (jobRunning_) return;
    newScrambleKeepingTimer();
    s.timer.setScramble(s.scramble);
    s.tap = TapState::IDLE;
}

bool App::setManualScramble(const std::string& text) {
    Lock g(mx);
    Moves m;
    std::string error;
    if (!parseMoves(text, m, &error)) {
        say(error);
        return false;
    }
    if (m.empty()) {
        say("Type at least one move");
        return false;
    }
    s.scramble = m;
    s.timer.setScramble(m);
    return true;
}

void App::armFree() {
    Lock g(mx);
    if (!jobRunning_) s.timer.armFree();
}

void App::tapTimer() {
    Lock g(mx);
    if (cube_) {
        armFree();
        say("Timer armed: turn to start");
        return;
    }
    uint32_t now = millis();
    if (s.tap != TapState::RUNNING) {
        s.tap = TapState::RUNNING;
        s.tapStartedAt = now;
    } else {
        s.tap = TapState::STOPPED;
        s.tapTimeMs = now - s.tapStartedAt;
        save(s.tapTimeMs);
        newScrambleKeepingTimer();
        s.timer.setScramble(s.scramble);
    }
}

void App::resetCube() {
    Lock g(mx);
    if (jobRunning_) return;
    if (cube_) {
        cube_->send(CubeCommand::REQUEST_RESET);
        cube_->send(CubeCommand::REQUEST_FACELETS);
    }
    s.orientation.reset();
    s.timer.reset();
    s.timer.onFacelets(CubieCube::solved());
    s.timer.setScramble(s.scramble);
    history_.clear();
    historyKnown_ = true;
    updateCanSolve();
    say("Cube marked as solved");
}

// ---------------------------------------------------------------- solves

void App::save(uint32_t timeMs) {
    s.solves.push_back({timeMs, Penalty::NONE});
    if (s.solves.size() > MAX_SOLVES) s.solves.erase(s.solves.begin());
    s.lastSolveAt = millis();
    beep(2093, 90);
    persist();
}

void App::setPenalty(int i, Penalty p) {
    Lock g(mx);
    if (i < 0 || i >= int(s.solves.size())) return;
    s.solves[i].penalty = s.solves[i].penalty == p ? Penalty::NONE : p;
    persist();
}

void App::deleteSolve(int i) {
    Lock g(mx);
    if (i < 0 || i >= int(s.solves.size())) return;
    s.solves.erase(s.solves.begin() + i);
    persist();
}

void App::persist() {
    std::vector<StoredSolve> raw;
    for (const Solve& v : s.solves) raw.push_back({v.timeMs, uint8_t(v.penalty)});
    if (raw.empty()) prefs.remove("solves");
    else prefs.putBytes("solves", raw.data(), raw.size() * sizeof(StoredSolve));
}

void App::load() {
    size_t len = prefs.getBytesLength("solves");
    std::vector<StoredSolve> raw(len / sizeof(StoredSolve));
    if (!raw.empty()) prefs.getBytes("solves", raw.data(), raw.size() * sizeof(StoredSolve));
    s.solves.clear();
    for (const StoredSolve& r : raw) s.solves.push_back({r.timeMs, Penalty(std::min<uint8_t>(r.penalty, 2))});
}

void App::toggleSound() {
    Lock g(mx);
    s.sound = !s.sound;
    prefs.putBool("sound", s.sound);
    say(s.sound ? "Sound on" : "Sound off");
}

// ---------------------------------------------------------------- worker task

void App::workerMain(void* self) {
    App* a = static_cast<App*>(self);
    for (;;) {
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
        Job j = a->pending_;
        a->pending_ = Job::NONE;
        if (j != Job::NONE) a->runJob(j);
    }
}

bool App::startJob(Job j) {
    Lock g(mx);
    if (jobRunning_) {
        say("Busy, one moment");
        return false;
    }
    jobRunning_ = true;
    pending_ = j;
    xTaskNotifyGive(static_cast<TaskHandle_t>(worker_));
    return true;
}

void App::runJob(Job j) {
    err_.clear();
    bool ok = true;
    const char* what = "";
    switch (j) {
        case Job::CONNECT: ok = jobConnect(); what = "Connect"; break;
        case Job::SCRAMBLE: ok = jobScramble(); what = "Robot scramble"; break;
        case Job::SOLVE: ok = jobSolve(); what = "Robot solve"; break;
        case Job::CHECK: ok = jobCheck(); what = "Orientation check"; break;
        default: break;
    }
    Lock g(mx);
    if (!ok) say(std::string(what) + " failed: " + err_);
    s.robot = RobotProgress();
    s.autoStatus.clear();
    if (j == Job::SOLVE || j == Job::CHECK) s.timer.setScramble(s.scramble);
    updateCanSolve();
    jobRunning_ = false;
}

void App::connect() { startJob(Job::CONNECT); }

void App::disconnectAll() {
    Lock g(mx);
    if (jobRunning_) return;
    cube_.reset();
    robot_.reset();
    s.cubeConnected = s.robotConnected = false;
    s.cubeInfo = CubeInfo();
    s.orientation.reset();
    say("Disconnected");
}

void App::robotScramble() {
    Lock g(mx);
    if (!robot_) return say("Connect the robot first (C)");
    if (!robot::canExecute(s.scramble)) return say("Scramble has U turns; press N for a robot scramble");
    startJob(Job::SCRAMBLE);
}

void App::robotSolve() {
    Lock g(mx);
    if (!robot_) return say("Connect the robot first (C)");
    if (!s.canSolve) return say(historyKnown_ ? "Cube is already solved" : "Unknown cube state: solve by hand, press X");
    startJob(Job::SOLVE);
}

void App::robotCheckOrientation() {
    Lock g(mx);
    if (!robot_) return say("Connect the robot first (C)");
    if (!cube_) return say("Put a connected smart cube in the robot first");
    startJob(Job::CHECK);
}

// ---------------------------------------------------------------- connect

int App::connectNow(const ScannedDevice& d) {
    {
        Lock g(mx);
        s.autoStatus = "Connecting " + d.name + "...";
    }
    NimBLEClient* c = ble::connect(d.address);
    if (!c) return 0;
    std::string mac = d.advertisedMac.empty() ? ble::addressString(d.address) : d.advertisedMac;
    if (auto cube = CubeClient::attach(c, d.name, mac)) {
        Lock g(mx);
        s.cubeName = d.name;
        s.cubeConnected = true;
        s.orientation.reset();
        cube_ = std::move(cube);
        prefs.putString("lastCube", addrKey(d.address).c_str());
        say("Connected " + d.name + " (" + protocolName(cube_->protocol()) + ")");
        return 1;
    }
    if (auto r = RobotClient::attach(c, d.name)) {
        Lock g(mx);
        s.robotName = d.name;
        s.robotConnected = true;
        s.orientation.reset();
        robot_ = std::move(r);
        prefs.putString("lastRobot", addrKey(d.address).c_str());
        say("Connected GAN Robot");
        return 2;
    }
    ble::release(c);
    say(d.name + " is not a supported cube or robot");
    return 0;
}

/**
 * Scan and try GAN devices one at a time until the wanted kind is connected:
 * the remembered device first, then one advertising the robot service, then strongest.
 */
bool App::findAndConnect(uint32_t timeoutMs, const std::string& remembered, bool wantRobot) {
    uint32_t start = millis();
    std::vector<std::string> tried;
    NimBLEAddress rememberedAddr;
    bool haveRemembered = parseAddrKey(remembered, rememberedAddr);
    auto done = [&] {
        Lock g(mx);
        return wantRobot ? robot_ != nullptr : cube_ != nullptr;
    };
    while (!done() && millis() - start < timeoutMs) {
        std::vector<ScannedDevice> found = ble::scan(2500);
        std::vector<ScannedDevice> todo;
        for (auto& d : found) {
            std::string a = ble::addressString(d.address);
            if (std::find(tried.begin(), tried.end(), a) != tried.end()) continue;
            {
                Lock g(mx);
                if (cube_ && ble::addressString(cube_->address()) == a) continue;
                if (robot_ && ble::addressString(robot_->address()) == a) continue;
            }
            todo.push_back(d);
        }
        std::stable_sort(todo.begin(), todo.end(), [&](const ScannedDevice& x, const ScannedDevice& y) {
            bool rx = haveRemembered && x.address == rememberedAddr, ry = haveRemembered && y.address == rememberedAddr;
            if (rx != ry) return rx;
            bool lx = x.looksLikeRobot == wantRobot, ly = y.looksLikeRobot == wantRobot;
            if (lx != ly) return lx;
            return x.rssi > y.rssi;
        });
        for (auto& d : todo) {
            if (done() || millis() - start > timeoutMs) break;
            tried.push_back(ble::addressString(d.address));
            connectNow(d);
        }
    }
    return done();
}

/**
 * One-tap Connect: find the robot, have it make a quick turn and back to wake the
 * smart cube inside it, then find the cube. Skips whatever is already connected.
 */
bool App::jobConnect() {
    bool haveRobot, haveCube;
    {
        Lock g(mx);
        haveRobot = robot_ != nullptr;
    }
    if (!haveRobot) {
        {
            Lock g(mx);
            s.autoStatus = "Looking for the robot...";
        }
        findAndConnect(20000, remembered("lastRobot"), true);
    }
    for (int attempt = 0; attempt < 2; attempt++) {
        {
            Lock g(mx);
            if (cube_) break;
            haveRobot = robot_ != nullptr;
        }
        if (haveRobot) {
            {
                Lock g(mx);
                s.autoStatus = "Robot is waking the cube...";
            }
            // A turn and back, so the move history is unchanged.
            robot_->scramble(WAKE_TURNS, nullptr);
        }
        {
            Lock g(mx);
            s.autoStatus = "Looking for the cube...";
        }
        findAndConnect(10000, remembered("lastCube"), false);
    }
    Lock g(mx);
    haveRobot = robot_ != nullptr;
    haveCube = cube_ != nullptr;
    if (haveRobot && haveCube) say("Robot and cube connected");
    else if (!haveRobot && !haveCube) say("No robot or cube found. Switched on?");
    else if (!haveRobot) say("Cube connected, no robot found");
    else say("Robot connected; turn the cube to wake it, then C");
    // A robot means the next scramble should be robot-safe.
    if (haveRobot && !robot::canExecute(s.scramble)) {
        newScrambleKeepingTimer();
        s.timer.setScramble(s.scramble);
    }
    return true;
}

// ---------------------------------------------------------------- robot jobs

bool App::execute(const Moves& moves, const std::string& label) {
    bool ok = robot_->scramble(moves, [&](int done, int total) {
        Lock g(mx);
        s.robot.active = true;
        s.robot.done = done;
        s.robot.total = total;
        s.robot.label = label;
    });
    if (!ok) {
        err_ = "lost the robot";
        return false;
    }
    Lock g(mx);
    // With a smart cube connected, its own move events already updated the history.
    if (!cube_ && historyKnown_) {
        history_.insert(history_.end(), moves.begin(), moves.end());
        if (CubieCube::solved().apply(history_).isSolved()) history_.clear();
    }
    return true;
}

bool App::observe(const Moves& moves, const std::string& label, Moves& seen) {
    size_t turns = robot::executed(moves).size();
    seen.clear();
    {
        Lock g(mx);
        recording_ = &seen;
    }
    bool ok = execute(moves, label);
    // BLE reports can trail the robot's idle status slightly.
    uint32_t start = millis();
    while (ok && millis() - start < 1000) {
        {
            Lock g(mx);
            if (seen.size() >= turns) break;
        }
        delay(10);
    }
    Lock g(mx);
    recording_ = nullptr;
    return ok;
}

bool App::runChecked(const Moves& moves, const RobotOrientation& o) {
    Moves todo = moves;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        Moves expected = o.toCube(robot::executed(todo));
        Moves seen;
        if (!observe(todo, attempt == 0 ? "Robot is turning" : "Missed a turn, fixing", seen)) return false;
        Moves fix = RobotOrientation::correction(expected, seen);
        if (CubieCube::solved().apply(fix).isSolved()) return true;
        todo = robotsolve::forRobot(fix, o);
    }
    err_ = "the robot kept missing turns";
    return false;
}

std::optional<RobotOrientation> App::checkOrientation(bool putBack) {
    Moves test = RobotOrientation::testTurns();
    if (putBack)
        for (int i = 2; i >= 0; i--) test.push_back(RobotOrientation::testTurns()[i].inverse());
    Moves sinceStart;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        Moves seen;
        if (!observe(test, attempt == 0 ? "Checking cube placement" : "Missed a turn, checking again", seen))
            return std::nullopt;
        sinceStart.insert(sinceStart.end(), seen.begin(), seen.end());
        auto o = RobotOrientation::fromObservations(robot::executed(test), seen);
        if (o) {
            {
                Lock g(mx);
                s.orientation = o;
            }
            if (putBack) {
                // Earlier attempts may have left turns behind: undo them too.
                Moves undo = robotsolve::solutionFor(sinceStart, *o);
                if (!undo.empty() && !runChecked(undo, *o)) return std::nullopt;
            }
            return o;
        }
        if (seen.empty()) {
            err_ = "the cube reported no turns. Is it the one in the robot?";
            return std::nullopt;
        }
    }
    err_ = "the robot kept missing turns";
    return std::nullopt;
}

bool App::jobScramble() {
    Moves moves;
    bool haveCube;
    {
        Lock g(mx);
        moves = s.scramble;
        haveCube = cube_ != nullptr;
        if (haveCube) s.timer.reset();  // keep test turns from moving the timer
    }
    if (!haveCube) return execute(moves, "Scrambling");
    // The cube may have been put back differently after the last solve, so check every time.
    auto o = checkOrientation(true);
    if (!o) {
        Lock g(mx);
        s.timer.setScramble(s.scramble);
        return false;
    }
    bool ok = runChecked(moves, *o);
    Lock g(mx);
    // The timer tracks the scramble in the smart cube's own frame.
    s.timer.setScramble(o->toCube(moves));
    return ok;
}

bool App::jobSolve() {
    bool haveCube;
    {
        Lock g(mx);
        haveCube = cube_ != nullptr;
        s.timer.reset();  // keep the robot's turns from starting or stopping the timer
    }
    if (!haveCube) {
        Moves h;
        {
            Lock g(mx);
            h = history_;
        }
        return execute(robotsolve::solutionFor(h, RobotOrientation::identity()), "Solving");
    }
    auto o = checkOrientation(false);
    if (!o) return false;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        Moves left;
        {
            Lock g(mx);
            if (!historyKnown_) {
                err_ = "cube state unknown";
                return false;
            }
            if (history_.empty()) return true;
            left = history_;
        }
        Moves seen;
        if (!observe(robotsolve::solutionFor(left, *o), attempt == 0 ? "Solving" : "Missed a turn, finishing", seen))
            return false;
    }
    Lock g(mx);
    if (history_.empty()) return true;
    err_ = "the robot kept missing turns";
    return false;
}

bool App::jobCheck() {
    {
        Lock g(mx);
        s.timer.reset();
    }
    auto o = checkOrientation(true);
    if (!o) return false;
    say(o->isIdentity() ? "Cube is placed as expected" : "Placement: " + o->describe());
    return true;
}

}  // namespace og
