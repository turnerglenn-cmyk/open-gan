// GAN smart cube protocol (Gen2, Gen3, Gen4).
//
// C++ port of the Open GAN Kotlin core, which ports gan-web-bluetooth by
// Andy Fedotov (MIT License), https://github.com/afedotov/gan-web-bluetooth
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <deque>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "aes128.h"
#include "cube.h"

namespace og {

using Bytes = std::vector<uint8_t>;

namespace gan {
constexpr const char* GEN2_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dc4179";
constexpr const char* GEN2_COMMAND = "28be4a4a-cd67-11e9-a32f-2a2ae2dbcce4";
constexpr const char* GEN2_STATE = "28be4cb6-cd67-11e9-a32f-2a2ae2dbcce4";
constexpr const char* GEN3_SERVICE = "8653000a-43e6-47b7-9cb0-5fc21d4ae340";
constexpr const char* GEN3_COMMAND = "8653000c-43e6-47b7-9cb0-5fc21d4ae340";
constexpr const char* GEN3_STATE = "8653000b-43e6-47b7-9cb0-5fc21d4ae340";
constexpr const char* GEN4_SERVICE = "00000010-0000-fff7-fff6-fff5fff4fff0";
constexpr const char* GEN4_COMMAND = "0000fff5-0000-1000-8000-00805f9b34fb";
constexpr const char* GEN4_STATE = "0000fff6-0000-1000-8000-00805f9b34fb";

/** Advertised name prefixes of supported cubes (and the GAN Robot). */
bool isGanName(const std::string& name);

struct Key {
    uint8_t key[16];
    uint8_t iv[16];
};
extern const Key KEY_GAN;     // GAN Gen2/3/4
extern const Key KEY_MOYU_AI; // MoYu AI 2023 ("AiCube")

/**
 * MAC from manufacturer data (company id already stripped): stored reversed in
 * the last 6 of the first 9 bytes. Returns "" if too short.
 */
std::string macFromManufacturerData(const uint8_t* data, size_t len);

/** Salt: MAC bytes reversed. Returns false if [mac] isn't 6 hex bytes. */
bool saltFromMac(const std::string& mac, uint8_t salt[6]);
}  // namespace gan

/** AES-128-CBC on the first 16 and last 16 bytes of each message, MAC-salted key and IV. */
class GanEncrypter {
public:
    GanEncrypter(const gan::Key& key, const uint8_t salt[6]);
    Bytes encrypt(const Bytes& data) const;
    Bytes decrypt(const Bytes& data) const;

private:
    void chunk(uint8_t* p, bool encrypt) const;
    uint8_t iv_[16];
    std::unique_ptr<Aes128> aes_;
};

/** Reads bit-aligned words from a message, MSB first. Bits past the end read as 0. */
class BitView {
public:
    BitView(const uint8_t* data, size_t len) : d_(data), len_(len) {}
    int bits(int start, int len) const;
    /** <=8 bits MSB first; 16/32 bits byte-wise with the given endianness. */
    int64_t word(int start, int len, bool littleEndian = false) const;
    int i(int start, int len, bool le = false) const { return int(word(start, len, le)); }

private:
    const uint8_t* d_;
    size_t len_;
};

enum class CubeCommand { REQUEST_HARDWARE, REQUEST_FACELETS, REQUEST_BATTERY, REQUEST_RESET };

constexpr int64_t NO_TS = INT64_MIN;

struct CubeEvent {
    enum Type { MOVE, FACELETS, BATTERY, HARDWARE } type = MOVE;
    int64_t timestamp = 0;
    int serial = 0;
    // MOVE
    Move move;
    int64_t localTs = NO_TS;  // NO_TS when recovered from history
    int64_t cubeTs = NO_TS;
    // FACELETS
    CubieCube state;
    // BATTERY
    int battery = 0;
    // HARDWARE
    std::string hwName, swVersion, hwVersion, productDate;
    bool gyro = false;

    static CubeEvent moveEvent(int64_t now, int serial, Face face, int direction, int64_t local, int64_t cube) {
        CubeEvent e;
        e.type = MOVE;
        e.timestamp = now;
        e.serial = serial;
        e.move = Move(face, direction == 0 ? 1 : 3);
        e.localTs = local;
        e.cubeTs = cube;
        return e;
    }
};

using CubeEvents = std::vector<CubeEvent>;

/** Sends plaintext commands (the link encrypts) and drops the connection. Must not block. */
class CubeLink {
public:
    virtual ~CubeLink() = default;
    virtual void sendCommand(const Bytes& message) = 0;
    virtual void disconnect() = 0;
};

enum class GanProtocol { GEN2, GEN3, GEN4 };
const char* protocolName(GanProtocol p);

class GanDriver {
public:
    virtual ~GanDriver() = default;
    virtual Bytes createCommand(CubeCommand c) = 0;
    virtual void handle(CubeLink& link, const Bytes& msg, int64_t now, CubeEvents& out) = 0;
};

std::unique_ptr<GanDriver> makeDriver(GanProtocol p);

class Gen2Driver : public GanDriver {
public:
    Bytes createCommand(CubeCommand c) override;
    void handle(CubeLink& link, const Bytes& msg, int64_t now, CubeEvents& out) override;

private:
    int lastSerial_ = -1;
    int64_t lastMoveTs_ = 0;
    int64_t cubeTs_ = 0;
};

/** Gen3/Gen4: moves go through a FIFO so serial gaps can be filled from move history. */
class BufferedMoveDriver : public GanDriver {
protected:
    int serial_ = -1;
    int lastSerial_ = -1;
    int64_t lastLocalTs_ = NO_TS;
    std::deque<CubeEvent> buffer_;

    virtual Bytes moveHistoryCommand(int serial, int count) = 0;
    void requestMoveHistory(CubeLink& link, int startSerial, int count);
    void evict(CubeLink* link, CubeEvents& out);
    void injectMissedMove(const CubeEvent& m);
    void checkIfMoveMissed(CubeLink& link);
    void onFaceletsSerial(CubeLink& link, int serial, int64_t now);
    static int historyFace(int raw);  // -1 if invalid
    static int liveFace(int raw);
};

class Gen3Driver : public BufferedMoveDriver {
public:
    Bytes createCommand(CubeCommand c) override;
    void handle(CubeLink& link, const Bytes& msg, int64_t now, CubeEvents& out) override;

protected:
    Bytes moveHistoryCommand(int serial, int count) override;
};

class Gen4Driver : public BufferedMoveDriver {
public:
    Bytes createCommand(CubeCommand c) override;
    void handle(CubeLink& link, const Bytes& msg, int64_t now, CubeEvents& out) override;

protected:
    Bytes moveHistoryCommand(int serial, int count) override;

private:
    std::map<int, std::string> hwInfo_;
};

}  // namespace og
