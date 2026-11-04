// SPDX-License-Identifier: GPL-3.0-or-later
// Port of gan-web-bluetooth protocol drivers (MIT License, Andy Fedotov).
#include "gan.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cstdio>
#include <cstring>

namespace og {

namespace gan {

bool isGanName(const std::string& n) {
    return n.rfind("GAN", 0) == 0 || n.rfind("MG", 0) == 0 || n.rfind("AiCube", 0) == 0;
}

const Key KEY_GAN = {
    {0x01, 0x02, 0x42, 0x28, 0x31, 0x91, 0x16, 0x07, 0x20, 0x05, 0x18, 0x54, 0x42, 0x11, 0x12, 0x53},
    {0x11, 0x03, 0x32, 0x28, 0x21, 0x01, 0x76, 0x27, 0x20, 0x95, 0x78, 0x14, 0x32, 0x12, 0x02, 0x43},
};

const Key KEY_MOYU_AI = {
    {0x05, 0x12, 0x02, 0x45, 0x02, 0x01, 0x29, 0x56, 0x12, 0x78, 0x12, 0x76, 0x81, 0x01, 0x08, 0x03},
    {0x01, 0x44, 0x28, 0x06, 0x86, 0x21, 0x22, 0x28, 0x51, 0x05, 0x08, 0x31, 0x82, 0x02, 0x21, 0x06},
};

std::string macFromManufacturerData(const uint8_t* data, size_t len) {
    if (len < 6) return "";
    size_t n = std::min<size_t>(9, len);
    std::string s;
    char buf[4];
    for (size_t i = 1; i <= 6; i++) {
        snprintf(buf, sizeof buf, "%02X", data[n - i]);
        if (i > 1) s += ':';
        s += buf;
    }
    return s;
}

bool saltFromMac(const std::string& mac, uint8_t salt[6]) {
    uint8_t parts[6];
    int count = 0;
    size_t i = 0;
    while (i < mac.size() && count < 7) {
        while (i < mac.size() && !isxdigit((unsigned char)mac[i])) i++;
        if (i >= mac.size()) break;
        size_t start = i;
        while (i < mac.size() && isxdigit((unsigned char)mac[i])) i++;
        if (count >= 6) return false;
        parts[count++] = uint8_t(strtol(mac.substr(start, i - start).c_str(), nullptr, 16));
    }
    if (count != 6) return false;
    for (int k = 0; k < 6; k++) salt[k] = parts[5 - k];
    return true;
}

}  // namespace gan

// ---------------------------------------------------------------- encrypter

GanEncrypter::GanEncrypter(const gan::Key& key, const uint8_t salt[6]) {
    uint8_t k[16];
    memcpy(k, key.key, 16);
    memcpy(iv_, key.iv, 16);
    for (int i = 0; i < 6; i++) {
        // Upstream uses modulo 0xFF (not 0x100); the cubes expect exactly this.
        k[i] = uint8_t((key.key[i] + salt[i]) % 0xFF);
        iv_[i] = uint8_t((key.iv[i] + salt[i]) % 0xFF);
    }
    aes_.reset(new Aes128(k));
}

void GanEncrypter::chunk(uint8_t* p, bool encrypt) const {
    if (encrypt) {
        for (int i = 0; i < 16; i++) p[i] ^= iv_[i];
        aes_->encryptBlock(p);
    } else {
        aes_->decryptBlock(p);
        for (int i = 0; i < 16; i++) p[i] ^= iv_[i];
    }
}

Bytes GanEncrypter::encrypt(const Bytes& data) const {
    Bytes r = data;
    if (r.size() < 16) return r;
    chunk(r.data(), true);
    if (r.size() > 16) chunk(r.data() + r.size() - 16, true);
    return r;
}

Bytes GanEncrypter::decrypt(const Bytes& data) const {
    Bytes r = data;
    if (r.size() < 16) return r;
    if (r.size() > 16) chunk(r.data() + r.size() - 16, false);
    chunk(r.data(), false);
    return r;
}

// ---------------------------------------------------------------- bit view

int BitView::bits(int start, int len) const {
    int v = 0;
    for (int i = start; i < start + len; i++) {
        size_t byte = size_t(i / 8);
        int b = byte < len_ ? (d_[byte] >> (7 - i % 8)) & 1 : 0;
        v = (v << 1) | b;
    }
    return v;
}

int64_t BitView::word(int start, int len, bool le) const {
    if (len <= 8) return bits(start, len);
    int n = len / 8;
    int64_t v = 0;
    for (int k = 0; k < n; k++) {
        int idx = le ? n - 1 - k : k;
        v = (v << 8) | bits(start + 8 * idx, 8);
    }
    return v;
}

// ---------------------------------------------------------------- shared helpers

namespace {

CubieCube readState(const BitView& m, int cpAt, int coAt, int epAt, int eoAt) {
    CubieCube c;
    int sum = 0;
    for (int i = 0; i < 7; i++) sum += (c.cp[i] = uint8_t(m.i(cpAt + i * 3, 3)));
    c.cp[7] = uint8_t(28 - sum);
    sum = 0;
    for (int i = 0; i < 7; i++) sum += (c.co[i] = uint8_t(m.i(coAt + i * 2, 2)));
    c.co[7] = uint8_t((3 - sum % 3) % 3);
    sum = 0;
    for (int i = 0; i < 11; i++) sum += (c.ep[i] = uint8_t(m.i(epAt + i * 4, 4)));
    c.ep[11] = uint8_t(66 - sum);
    sum = 0;
    for (int i = 0; i < 11; i++) sum += (c.eo[i] = uint8_t(m.i(eoAt + i, 1)));
    c.eo[11] = uint8_t((2 - sum % 2) % 2);
    return c;
}

Bytes fill(size_t size, std::initializer_list<int> bytes) {
    Bytes b(size, 0);
    size_t i = 0;
    for (int v : bytes) b[i++] = uint8_t(v);
    return b;
}

std::string str(const char* fmt, int a, int b) {
    char buf[24];
    snprintf(buf, sizeof buf, fmt, a, b);
    return buf;
}

CubeEvent facelets(int64_t now, int serial, const CubieCube& s) {
    CubeEvent e;
    e.type = CubeEvent::FACELETS;
    e.timestamp = now;
    e.serial = serial;
    e.state = s;
    return e;
}

CubeEvent battery(int64_t now, int level) {
    CubeEvent e;
    e.type = CubeEvent::BATTERY;
    e.timestamp = now;
    e.battery = std::min(level, 100);
    return e;
}

std::string chars(const BitView& m, int start, int count) {
    std::string s;
    for (int i = 0; i < count; i++) s += char(m.i(start + i * 8, 8));
    while (!s.empty() && s.back() == '\0') s.pop_back();
    return s;
}

}  // namespace

const char* protocolName(GanProtocol p) {
    switch (p) {
        case GanProtocol::GEN2: return "gen2";
        case GanProtocol::GEN3: return "gen3";
        default: return "gen4";
    }
}

std::unique_ptr<GanDriver> makeDriver(GanProtocol p) {
    switch (p) {
        case GanProtocol::GEN2: return std::unique_ptr<GanDriver>(new Gen2Driver());
        case GanProtocol::GEN3: return std::unique_ptr<GanDriver>(new Gen3Driver());
        default: return std::unique_ptr<GanDriver>(new Gen4Driver());
    }
}

// ---------------------------------------------------------------- Gen2

Bytes Gen2Driver::createCommand(CubeCommand c) {
    switch (c) {
        case CubeCommand::REQUEST_FACELETS: return fill(20, {0x04});
        case CubeCommand::REQUEST_HARDWARE: return fill(20, {0x05});
        case CubeCommand::REQUEST_BATTERY: return fill(20, {0x09});
        default: return fill(20, {0x0A, 0x05, 0x39, 0x77, 0x00, 0x00, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB});
    }
}

void Gen2Driver::handle(CubeLink& link, const Bytes& msg, int64_t now, CubeEvents& out) {
    BitView m(msg.data(), msg.size());
    switch (m.i(0, 4)) {
        case 0x02: {
            if (lastSerial_ == -1) break;  // only after the first facelets event
            int serial = m.i(4, 8);
            int diff = std::min((serial - lastSerial_) & 0xFF, 7);
            lastSerial_ = serial;
            if (diff > 0) {
                for (int i = diff - 1; i >= 0; i--) {
                    int face = m.i(12 + 5 * i, 4);
                    int dir = m.i(16 + 5 * i, 1);
                    int64_t elapsed = m.word(47 + 16 * i, 16);
                    if (elapsed == 0) elapsed = now - lastMoveTs_;  // 16-bit register overflow
                    cubeTs_ += elapsed;
                    if (face >= 0 && face < 6)
                        out.push_back(CubeEvent::moveEvent(now, (serial - i) & 0xFF, Face(face), dir,
                                                           i == 0 ? now : NO_TS, cubeTs_));
                }
                lastMoveTs_ = now;
            }
            break;
        }
        case 0x04: {
            int serial = m.i(4, 8);
            if (lastSerial_ == -1) lastSerial_ = serial;
            out.push_back(facelets(now, serial, readState(m, 12, 33, 47, 91)));
            break;
        }
        case 0x05: {
            CubeEvent e;
            e.type = CubeEvent::HARDWARE;
            e.timestamp = now;
            e.hwName = chars(m, 40, 8);
            e.hwVersion = str("%d.%d", m.i(8, 8), m.i(16, 8));
            e.swVersion = str("%d.%d", m.i(24, 8), m.i(32, 8));
            e.gyro = m.i(104, 1) == 1;
            out.push_back(e);
            break;
        }
        case 0x09: out.push_back(battery(now, m.i(8, 8))); break;
        case 0x0D: link.disconnect(); break;
        default: break;  // 0x01 gyro: not used
    }
}

// ---------------------------------------------------------------- Gen3/Gen4 buffer

void BufferedMoveDriver::requestMoveHistory(CubeLink& link, int startSerial, int count) {
    // History responses are byte aligned and start at an odd serial.
    int s = startSerial;
    if (s % 2 == 0) s = (s - 1) & 0xFF;
    if (count % 2 == 1) count++;
    // Never cross the 255 -> 0 edge; firmware spoofs those moves as 'D'.
    count = std::min(count, s + 1);
    link.sendCommand(moveHistoryCommand(s, count));
}

void BufferedMoveDriver::evict(CubeLink* link, CubeEvents& out) {
    while (!buffer_.empty()) {
        const CubeEvent& head = buffer_.front();
        int diff = lastSerial_ == -1 ? 1 : (head.serial - lastSerial_) & 0xFF;
        if (diff > 1) {
            if (link) requestMoveHistory(*link, head.serial, diff);
            break;
        }
        lastSerial_ = head.serial;
        out.push_back(head);
        buffer_.pop_front();
    }
    // The buffer is stuck; force a reconnect rather than silently drifting.
    if (link && buffer_.size() > 16) link->disconnect();
}

static bool serialInRange(int start, int end, int s, bool closedStart = false, bool closedEnd = false) {
    return ((end - start) & 0xFF) >= ((s - start) & 0xFF) && (closedStart || ((start - s) & 0xFF) > 0) &&
           (closedEnd || ((end - s) & 0xFF) > 0);
}

void BufferedMoveDriver::injectMissedMove(const CubeEvent& m) {
    if (!buffer_.empty()) {
        const CubeEvent& head = buffer_.front();
        for (const CubeEvent& e : buffer_)
            if (e.serial == m.serial) return;
        if (!serialInRange(lastSerial_, head.serial, m.serial)) return;
        // History arrives newest-first, so only the move just before the head fits.
        if (m.serial == ((head.serial - 1) & 0xFF)) buffer_.push_front(m);
    } else if (serialInRange(lastSerial_, serial_, m.serial, false, true)) {
        buffer_.push_front(m);  // recovered via the periodic facelets event
    }
}

void BufferedMoveDriver::checkIfMoveMissed(CubeLink& link) {
    int diff = (serial_ - lastSerial_) & 0xFF;
    if (diff > 0 && serial_ != 0) {  // serial 0 triggers a firmware bug
        int start = buffer_.empty() ? ((serial_ + 1) & 0xFF) : buffer_.front().serial;
        requestMoveHistory(link, start, diff + 1);
    }
}

void BufferedMoveDriver::onFaceletsSerial(CubeLink& link, int s, int64_t now) {
    serial_ = s;
    if (lastSerial_ != -1) {
        // Debounce while the cube is actively being turned.
        if (lastLocalTs_ != NO_TS && now - lastLocalTs_ > 500) checkIfMoveMissed(link);
    }
    if (lastSerial_ == -1) lastSerial_ = s;
}

int BufferedMoveDriver::historyFace(int raw) {
    static const int codes[6] = {1, 5, 3, 0, 4, 2};
    for (int i = 0; i < 6; i++)
        if (codes[i] == raw) return i;
    return -1;
}

int BufferedMoveDriver::liveFace(int raw) {
    static const int codes[6] = {2, 32, 8, 1, 16, 4};
    for (int i = 0; i < 6; i++)
        if (codes[i] == raw) return i;
    return -1;
}

// ---------------------------------------------------------------- Gen3

Bytes Gen3Driver::createCommand(CubeCommand c) {
    switch (c) {
        case CubeCommand::REQUEST_FACELETS: return fill(16, {0x68, 0x01});
        case CubeCommand::REQUEST_HARDWARE: return fill(16, {0x68, 0x04});
        case CubeCommand::REQUEST_BATTERY: return fill(16, {0x68, 0x07});
        default:
            return fill(16, {0x68, 0x05, 0x05, 0x39, 0x77, 0x00, 0x00, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB});
    }
}

Bytes Gen3Driver::moveHistoryCommand(int serial, int count) {
    Bytes b(16, 0);
    b[0] = 0x68;
    b[1] = 0x03;
    b[2] = uint8_t(serial);
    b[4] = uint8_t(count);
    return b;
}

void Gen3Driver::handle(CubeLink& link, const Bytes& msg, int64_t now, CubeEvents& out) {
    BitView m(msg.data(), msg.size());
    int magic = m.i(0, 8), type = m.i(8, 8), len = m.i(16, 8);
    if (magic != 0x55 || len <= 0) return;
    switch (type) {
        case 0x01: {
            if (lastSerial_ == -1) break;
            lastLocalTs_ = now;
            int64_t cubeTs = m.word(24, 32, true);
            serial_ = m.i(56, 16, true);
            int face = liveFace(m.i(74, 6));
            if (face >= 0) buffer_.push_back(CubeEvent::moveEvent(now, serial_, Face(face), m.i(72, 2), now, cubeTs));
            evict(&link, out);
            break;
        }
        case 0x06: {
            int start = m.i(24, 8);
            int count = (len - 1) * 2;
            for (int i = 0; i < count; i++) {
                int face = historyFace(m.i(32 + 4 * i, 3));
                if (face < 0) continue;
                injectMissedMove(CubeEvent::moveEvent(now, (start - i) & 0xFF, Face(face), m.i(35 + 4 * i, 1), NO_TS, NO_TS));
            }
            evict(nullptr, out);
            break;
        }
        case 0x02: {
            int s = m.i(24, 16, true);
            onFaceletsSerial(link, s, now);
            out.push_back(facelets(now, s, readState(m, 40, 61, 77, 121)));
            break;
        }
        case 0x07: {
            CubeEvent e;
            e.type = CubeEvent::HARDWARE;
            e.timestamp = now;
            e.hwName = chars(m, 32, 5);
            e.swVersion = str("%d.%d", m.i(72, 4), m.i(76, 4));
            e.hwVersion = str("%d.%d", m.i(80, 4), m.i(84, 4));
            out.push_back(e);
            break;
        }
        case 0x10: out.push_back(battery(now, m.i(24, 8))); break;
        case 0x11: link.disconnect(); break;
        default: break;
    }
}

// ---------------------------------------------------------------- Gen4

Bytes Gen4Driver::createCommand(CubeCommand c) {
    switch (c) {
        case CubeCommand::REQUEST_FACELETS: return fill(20, {0xDD, 0x04, 0x00, 0xED, 0x00, 0x00});
        case CubeCommand::REQUEST_HARDWARE: hwInfo_.clear(); return fill(20, {0xDF, 0x03, 0x00, 0x00, 0x00});
        case CubeCommand::REQUEST_BATTERY: return fill(20, {0xDD, 0x04, 0x00, 0xEF, 0x00, 0x00});
        default:
            return fill(20, {0xD2, 0x0D, 0x05, 0x39, 0x77, 0x00, 0x00, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB});
    }
}

Bytes Gen4Driver::moveHistoryCommand(int serial, int count) {
    Bytes b(20, 0);
    b[0] = 0xD1;
    b[1] = 0x04;
    b[2] = uint8_t(serial);
    b[4] = uint8_t(count);
    return b;
}

void Gen4Driver::handle(CubeLink& link, const Bytes& msg, int64_t now, CubeEvents& out) {
    BitView m(msg.data(), msg.size());
    int type = m.i(0, 8), len = m.i(8, 8);
    if (type == 0x01) {
        if (lastSerial_ == -1) return;
        lastLocalTs_ = now;
        int64_t cubeTs = m.word(16, 32, true);
        serial_ = m.i(48, 16, true);
        int face = liveFace(m.i(66, 6));
        if (face >= 0) buffer_.push_back(CubeEvent::moveEvent(now, serial_, Face(face), m.i(64, 2), now, cubeTs));
        evict(&link, out);
    } else if (type == 0xD1) {
        int start = m.i(16, 8);
        int count = (len - 1) * 2;
        for (int i = 0; i < count; i++) {
            int face = historyFace(m.i(24 + 4 * i, 3));
            if (face < 0) continue;
            injectMissedMove(CubeEvent::moveEvent(now, (start - i) & 0xFF, Face(face), m.i(27 + 4 * i, 1), NO_TS, NO_TS));
        }
        evict(nullptr, out);
    } else if (type == 0xED) {
        int s = m.i(16, 16, true);
        onFaceletsSerial(link, s, now);
        out.push_back(facelets(now, s, readState(m, 32, 53, 69, 113)));
    } else if (type >= 0xFA && type <= 0xFE) {
        char buf[16];
        switch (type) {
            case 0xFA:
                snprintf(buf, sizeof buf, "%04d-%02d-%02d", m.i(24, 16, true), m.i(40, 8), m.i(48, 8));
                hwInfo_[type] = buf;
                break;
            case 0xFC: hwInfo_[type] = chars(m, 24, std::max(len - 1, 0)); break;
            case 0xFD:
            case 0xFE: hwInfo_[type] = str("%d.%d", m.i(24, 4), m.i(28, 4)); break;
            default: break;
        }
        if (hwInfo_.size() == 4) {
            CubeEvent e;
            e.type = CubeEvent::HARDWARE;
            e.timestamp = now;
            e.hwName = hwInfo_[0xFC];
            e.hwVersion = hwInfo_[0xFE];
            e.swVersion = hwInfo_[0xFD];
            e.productDate = hwInfo_[0xFA];
            e.gyro = e.hwName == "GAN12uiM";
            out.push_back(e);
        }
    } else if (type == 0xEF) {
        out.push_back(battery(now, m.i(8 + len * 8, 8)));
    } else if (type == 0xEA) {
        link.disconnect();
    }
}

}  // namespace og
