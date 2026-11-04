// Open GAN for the M5Stack Cardputer ADV.
// GAN Robot scrambler + GAN smart cube (Gen2/3/4) timer. No ads, no tracking, no Wi-Fi.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <Arduino.h>
#include <M5Cardputer.h>

#include "app.h"

using namespace og;

namespace {

M5Canvas canvas;  // no parent here: M5Cardputer.Display is not initialised yet during static init
constexpr int W = 240, H = 135;

enum class Screen { MAIN, SOLVES, HELP, TYPE };
Screen screen = Screen::MAIN;
std::string typed;
int selected = 0;  // index from newest in the solves list

// Cube colours (white, yellow, red, orange, blue, green), like the Android theme.
const uint16_t CUBE_COLORS[6] = {0xFFFF, 0xFFE0, 0xF800, 0xFC00, 0x041F, 0x07E0};
const uint16_t GREY = 0x8410, DIM = 0x4208, BG = 0x0000, PANEL = 0x18E3;

void header(const AppState& s, const char* title) {
    canvas.fillRect(0, 0, W, 14, PANEL);
    for (int i = 0; i < 6; i++) canvas.fillRect(3 + i * 5, 4, 4, 6, CUBE_COLORS[i]);
    canvas.setFont(&fonts::Font0);
    canvas.setTextSize(1);
    canvas.setTextColor(TFT_WHITE);
    canvas.setTextDatum(middle_left);
    canvas.drawString(title, 36, 7);
    canvas.setTextDatum(middle_right);
    std::string cube = s.cubeConnected ? "Cube" : "cube";
    if (s.cubeConnected && s.cubeInfo.battery >= 0) cube += " " + std::to_string(s.cubeInfo.battery) + "%";
    canvas.setTextColor(s.cubeConnected ? TFT_GREEN : DIM);
    canvas.drawString(cube.c_str(), W - 4, 7);
    int cw = canvas.textWidth(cube.c_str());
    canvas.setTextColor(s.robotConnected ? TFT_GREEN : DIM);
    canvas.drawString(s.robotConnected ? "Robot" : "robot", W - 12 - cw, 7);
}

/** Draws the scramble word-wrapped; applied moves dim, the next one highlighted. */
void drawScramble(const AppState& s, int y0, int y1) {
    canvas.setFont(&fonts::Font2);
    canvas.setTextDatum(top_left);
    bool tracking = s.cubeConnected && s.timer.phase() == SmartTimer::Phase::SCRAMBLING;
    int applied = tracking ? s.timer.applied() : -1;
    int x = 4, y = y0, lineH = 16;
    for (size_t i = 0; i < s.scramble.size(); i++) {
        std::string m = s.scramble[i].str();
        int w = canvas.textWidth(m.c_str()) + 7;
        if (x + w > W - 2) {
            x = 4;
            y += lineH;
        }
        if (y + lineH > y1) break;
        uint16_t c = TFT_WHITE;
        if (applied >= 0 && int(i) < applied) c = DIM;
        else if (applied >= 0 && int(i) == applied) c = TFT_YELLOW;
        canvas.setTextColor(c);
        canvas.drawString(m.c_str(), x, y);
        x += w;
    }
}

void drawStatsLine(const AppState& s, int y) {
    canvas.setFont(&fonts::Font0);
    canvas.setTextDatum(top_left);
    canvas.setTextColor(GREY);
    std::string line = "ao5 " + stats::format(stats::averageOf(s.solves, 5)) + "  ao12 " +
                       stats::format(stats::averageOf(s.solves, 12)) + "  best " + stats::format(stats::best(s.solves)) +
                       "  #" + std::to_string(s.solves.size());
    canvas.drawString(line.c_str(), 4, y);
}

void drawConfetti(uint32_t since) {
    if (since > 1500) return;
    randomSeed(since / 60);
    for (int i = 0; i < 40; i++) {
        int x = random(W), y = (random(H) + int(since / 8)) % H;
        canvas.fillRect(x, y, 4, 3, CUBE_COLORS[random(6)]);
    }
}

void drawMain(const AppState& s) {
    header(s, "Open GAN");
    uint32_t now = millis();

    if (s.robot.active) {
        canvas.setFont(&fonts::Font2);
        canvas.setTextDatum(top_left);
        canvas.setTextColor(TFT_CYAN);
        canvas.drawString(s.robot.label.c_str(), 4, 20);
        int total = std::max(1, s.robot.total);
        canvas.drawRect(4, 42, W - 8, 10, GREY);
        canvas.fillRect(5, 43, (W - 10) * s.robot.done / total, 8, TFT_CYAN);
    } else {
        drawScramble(s, 17, 64);
    }

    // Big time.
    std::string time = "0.00";
    uint16_t color = GREY;
    std::string hint;
    using P = SmartTimer::Phase;
    if (s.tap == TapState::RUNNING) {
        time = stats::format(now - s.tapStartedAt);
        color = TFT_WHITE;
        hint = "Space to stop";
    } else if (s.tap == TapState::STOPPED && !s.cubeConnected) {
        time = stats::format(s.tapTimeMs);
        color = TFT_YELLOW;
    }
    if (s.tap != TapState::RUNNING && s.cubeConnected) {
        switch (s.timer.phase()) {
            case P::SCRAMBLING:
                hint = "Scramble: " + std::to_string(s.timer.applied()) + "/" + std::to_string(s.scramble.size());
                break;
            case P::READY:
                color = TFT_GREEN;
                hint = "Ready! First turn starts the timer";
                break;
            case P::RUNNING:
                time = stats::format(int64_t(now) - s.timer.startedAt());
                color = TFT_WHITE;
                hint = std::to_string(s.timer.moveCount()) + " moves";
                break;
            case P::FINISHED: {
                const SolveResult& r = s.timer.result();
                time = stats::format(r.timeMs);
                color = TFT_YELLOW;
                char buf[48];
                snprintf(buf, sizeof buf, "%d moves, %.2f tps", r.moveCount, r.tps());
                hint = buf;
                break;
            }
            case P::IDLE: break;
        }
    } else if (!s.cubeConnected && s.tap == TapState::IDLE) {
        hint = s.robotConnected ? "R robot scramble   Space timer" : "C connect   Space timer   H help";
    }
    canvas.setFont(&fonts::Font7);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(color);
    canvas.drawString(time.c_str(), W / 2, 90);

    // Status line: message, connect progress or hint.
    canvas.setFont(&fonts::Font0);
    canvas.setTextDatum(top_center);
    if (!s.autoStatus.empty()) {
        canvas.setTextColor(TFT_CYAN);
        canvas.drawString(s.autoStatus.c_str(), W / 2, 116);
    } else if (!s.message.empty() && now - s.messageAt < 3500) {
        canvas.setTextColor(TFT_ORANGE);
        canvas.drawString(s.message.c_str(), W / 2, 116);
    } else if (!hint.empty()) {
        canvas.setTextColor(GREY);
        canvas.drawString(hint.c_str(), W / 2, 116);
    }
    drawStatsLine(s, 126);
    if (s.lastSolveAt) drawConfetti(now - s.lastSolveAt);
}

void drawSolves(const AppState& s) {
    header(s, "Solves");
    int n = int(s.solves.size());
    canvas.setFont(&fonts::Font0);
    canvas.setTextDatum(top_left);
    canvas.setTextColor(GREY);
    std::string top = "mean " + stats::format(stats::mean(s.solves)) + "  ao100 " +
                      stats::format(stats::averageOf(s.solves, 100));
    canvas.drawString(top.c_str(), 4, 17);
    if (n == 0) {
        canvas.setTextColor(TFT_WHITE);
        canvas.drawString("No solves yet.", 4, 40);
    }
    selected = std::max(0, std::min(selected, n - 1));
    int first = std::max(0, selected - 4);
    canvas.setFont(&fonts::Font2);
    for (int row = 0; row < 6 && first + row < n; row++) {
        int idx = n - 1 - (first + row);  // newest first
        const Solve& v = s.solves[idx];
        int y = 28 + row * 16;
        bool sel = first + row == selected;
        if (sel) canvas.fillRect(0, y, W, 16, PANEL);
        canvas.setTextColor(sel ? TFT_YELLOW : TFT_WHITE);
        std::string t = std::to_string(idx + 1) + ".  " + stats::format(v.effective());
        if (v.penalty == Penalty::PLUS_TWO) t += "+";
        canvas.drawString(t.c_str(), 6, y);
    }
    canvas.setFont(&fonts::Font0);
    canvas.setTextColor(GREY);
    canvas.drawString("; . move  2 +2  D DNF  Del delete  ` back", 4, 126);
}

void drawHelp(const AppState& s) {
    header(s, "Keys");
    const char* lines[] = {
        "C  connect robot + cube    K  disconnect",
        "N  new scramble            T  type scramble",
        "R  robot scramble          S  robot solve",
        "O  check cube placement    X  cube is solved",
        "Space  arm / tap timer     L  solves list",
        "2 / D  +2 / DNF last solve M  sound on/off",
        "",
        "Free and open source (GPL-3.0). No ads,",
        "no tracking. Like it? Donate: see README.",
    };
    canvas.setFont(&fonts::Font0);
    canvas.setTextDatum(top_left);
    for (int i = 0; i < 9; i++) {
        canvas.setTextColor(i < 6 ? TFT_WHITE : TFT_GREEN);
        canvas.drawString(lines[i], 4, 19 + i * 12);
    }
}

void drawType(const AppState& s) {
    header(s, "Type a scramble");
    canvas.setFont(&fonts::Font2);
    canvas.setTextDatum(top_left);
    canvas.setTextColor(TFT_WHITE);
    std::string shown = typed + ((millis() / 400) % 2 ? "_" : " ");
    // Wrap at the screen width.
    int y = 22;
    size_t pos = 0;
    while (pos < shown.size() && y < 110) {
        size_t len = 1;
        while (pos + len < shown.size() && canvas.textWidth(shown.substr(pos, len + 1).c_str()) < W - 8) len++;
        canvas.drawString(shown.substr(pos, len).c_str(), 4, y);
        pos += len;
        y += 16;
    }
    canvas.setFont(&fonts::Font0);
    canvas.setTextColor(GREY);
    canvas.drawString("e.g. R U2 F'   Enter use   ` cancel", 4, 126);
}

void draw() {
    canvas.fillSprite(BG);
    {
        std::lock_guard<std::recursive_mutex> g(app.mx);
        const AppState& s = app.s;
        switch (screen) {
            case Screen::MAIN: drawMain(s); break;
            case Screen::SOLVES: drawSolves(s); break;
            case Screen::HELP: drawHelp(s); break;
            case Screen::TYPE: drawType(s); break;
        }
    }
    canvas.pushSprite(&M5Cardputer.Display, 0, 0);
}

int lastSolveIndex() {
    std::lock_guard<std::recursive_mutex> g(app.mx);
    return int(app.s.solves.size()) - 1;
}

void onKey(char k, bool enter, bool del) {
    if (screen == Screen::TYPE) {
        if (enter) {
            if (app.setManualScramble(typed)) screen = Screen::MAIN;
        } else if (del) {
            if (!typed.empty()) typed.pop_back();
        } else if (k == '`') {
            screen = Screen::MAIN;
        } else if (k) {
            char c = k;
            if (strchr("urfdlb", c)) c = char(toupper(c));
            if (typed.size() < 200) typed += c;
        }
        return;
    }
    if (screen == Screen::SOLVES) {
        int n = lastSolveIndex() + 1;
        int idx = n - 1 - selected;
        if (k == ';') selected = std::max(0, selected - 1);
        else if (k == '.') selected = std::min(std::max(n - 1, 0), selected + 1);
        else if (k == '2') app.setPenalty(idx, Penalty::PLUS_TWO);
        else if (k == 'd') app.setPenalty(idx, Penalty::DNF);
        else if (del) app.deleteSolve(idx);
        else if (k == '`' || k == 'l' || enter) screen = Screen::MAIN;
        return;
    }
    if (screen == Screen::HELP) {
        screen = Screen::MAIN;
        return;
    }
    switch (k) {
        case 'c': app.connect(); break;
        case 'k': app.disconnectAll(); break;
        case 'n': app.newScramble(); break;
        case 'r': app.robotScramble(); break;
        case 's': app.robotSolve(); break;
        case 'o': app.robotCheckOrientation(); break;
        case 'x': app.resetCube(); break;
        case ' ': app.tapTimer(); break;
        case 't':
            typed.clear();
            screen = Screen::TYPE;
            break;
        case 'l':
            selected = 0;
            screen = Screen::SOLVES;
            break;
        case 'h': screen = Screen::HELP; break;
        case '2': app.setPenalty(lastSolveIndex(), Penalty::PLUS_TWO); break;
        case 'd': app.setPenalty(lastSolveIndex(), Penalty::DNF); break;
        case 'm': app.toggleSound(); break;
        default:
            if (enter) app.tapTimer();
            break;
    }
}

}  // namespace

void setup() {
    auto cfg = M5.config();
    M5Cardputer.begin(cfg, true);
    M5Cardputer.Display.setRotation(1);
    M5Cardputer.Display.setBrightness(110);
    M5Cardputer.Speaker.setVolume(90);
    canvas.setColorDepth(16);
    canvas.createSprite(W, H);
    ble::begin();
    app.begin();
}

void loop() {
    M5Cardputer.update();
    if (M5Cardputer.Keyboard.isChange() && M5Cardputer.Keyboard.isPressed()) {
        Keyboard_Class::KeysState st = M5Cardputer.Keyboard.keysState();
        if (st.word.empty()) {
            onKey(0, st.enter, st.del);
        } else {
            for (char c : st.word) onKey(screen == Screen::TYPE ? c : char(tolower(c)), st.enter, st.del);
        }
    }
    app.loop();
    static uint32_t lastDraw = 0;
    if (millis() - lastDraw >= 33) {  // ~30 fps
        lastDraw = millis();
        draw();
    }
    delay(2);
}
