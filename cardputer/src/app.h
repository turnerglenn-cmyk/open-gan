// Open GAN app logic for the Cardputer: the same flow as the Android MainViewModel.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "ble.h"
#include "core/robot.h"
#include "core/timer.h"

namespace og {

struct CubeInfo {
    int battery = -1;
    std::string hardware;
};

struct RobotProgress {
    bool active = false;
    int done = 0, total = 0;
    std::string label;
};

enum class TapState { IDLE, RUNNING, STOPPED };

/** Everything the screen shows. Read and change it only while holding [App::mx]. */
struct AppState {
    SmartTimer timer;
    Moves scramble;
    std::vector<Solve> solves;
    CubeInfo cubeInfo;
    RobotProgress robot;
    std::string autoStatus;  // one-tap Connect status, empty when idle
    std::string message;
    uint32_t messageAt = 0;
    uint32_t lastSolveAt = 0;  // for the celebration
    TapState tap = TapState::IDLE;
    uint32_t tapStartedAt = 0;
    uint32_t tapTimeMs = 0;
    bool cubeConnected = false, robotConnected = false;
    std::string cubeName, robotName;
    bool canSolve = false;
    std::optional<RobotOrientation> orientation;
    bool sound = true;
};

class App {
public:
    std::recursive_mutex mx;
    AppState s;

    void begin();
    /** Main loop: cube events, disconnects, finished jobs. */
    void loop();

    // Actions from the keyboard. Long ones run on the worker task.
    void newScramble();
    bool setManualScramble(const std::string& text);
    void connect();
    void disconnectAll();
    void robotScramble();
    void robotSolve();
    void robotCheckOrientation();
    void resetCube();
    void armFree();
    void tapTimer();
    void setPenalty(int index, Penalty p);
    void deleteSolve(int index);
    void toggleSound();
    bool busy() const { return jobRunning_; }

private:
    enum class Job { NONE, CONNECT, SCRAMBLE, SOLVE, CHECK };
    static void workerMain(void* self);
    void runJob(Job j);
    bool startJob(Job j);

    // Worker-side helpers (block). Return false and set err_ on failure.
    bool jobConnect();
    bool findAndConnect(uint32_t timeoutMs, const std::string& remembered, bool wantRobot);
    int connectNow(const ScannedDevice& d);  // 0 none, 1 cube, 2 robot
    bool jobScramble();
    bool jobSolve();
    bool jobCheck();
    std::optional<RobotOrientation> checkOrientation(bool putBack);
    bool runChecked(const Moves& moves, const RobotOrientation& o);
    bool observe(const Moves& moves, const std::string& label, Moves& seen);
    bool execute(const Moves& moves, const std::string& label);

    void onCubeEvent(const CubeEvent& e);
    void onPhase(SmartTimer::Phase p);
    void save(uint32_t timeMs);
    void persist();
    void load();
    void say(const std::string& m);
    void updateCanSolve();
    void newScrambleKeepingTimer();
    void beep(int freq, int ms);

    std::unique_ptr<CubeClient> cube_;
    std::unique_ptr<RobotClient> robot_;
    std::unique_ptr<Scrambler> scrambler_;
    /** Moves since the cube was last solved; unknown when the cube reported a state we didn't track. */
    Moves history_;
    bool historyKnown_ = true;
    Moves* recording_ = nullptr;
    std::string err_;

    volatile bool jobRunning_ = false;
    volatile Job pending_ = Job::NONE;
    void* worker_ = nullptr;
};

extern App app;

}  // namespace og
