// Bluetooth LE for the Cardputer: scanning, GAN smart cube and GAN Robot clients (NimBLE).
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <NimBLEDevice.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "core/gan.h"
#include "core/robot.h"

namespace og {

struct ScannedDevice {
    std::string name;
    NimBLEAddress address;
    int rssi = -127;
    /** MAC from GAN manufacturer data, salts the cube's encryption key. */
    std::string advertisedMac;
    /** Advertises the robot's FFF0 service. */
    bool looksLikeRobot = false;
};

namespace ble {
void begin();
/** Blocking scan for GAN devices (cubes and robots), strongest first. */
std::vector<ScannedDevice> scan(uint32_t ms);
/** Connect to a device and discover services; nullptr on failure. */
NimBLEClient* connect(const NimBLEAddress& address);
void release(NimBLEClient* client);
std::string addressString(const NimBLEAddress& a);
}  // namespace ble

/** A raw notification from the cube, copied out of the BLE task. */
struct RawNotification {
    uint8_t len;
    uint8_t data[20];
    uint32_t at;
};

/**
 * A connected smart cube. BLE callbacks only queue raw bytes; [poll] runs on the main
 * loop, decrypts, feeds the protocol driver and sends any queued commands.
 */
class CubeClient : public CubeLink {
public:
    /** Takes ownership of [client] if it is a GAN cube; returns nullptr otherwise. */
    static std::unique_ptr<CubeClient> attach(NimBLEClient* client, const std::string& name, const std::string& mac);
    ~CubeClient() override;

    /** Process queued notifications (main loop). */
    void poll(CubeEvents& out);
    /** Queue a command; sent on the next poll. Safe from any task. */
    void send(CubeCommand c);
    void sendCommand(const Bytes& plain) override;
    void disconnect() override;
    bool connected() const;

    const std::string& name() const { return name_; }
    GanProtocol protocol() const { return protocol_; }
    NimBLEAddress address() const;

private:
    CubeClient() = default;
    NimBLEClient* client_ = nullptr;
    NimBLERemoteCharacteristic* command_ = nullptr;
    NimBLERemoteCharacteristic* state_ = nullptr;
    GanProtocol protocol_ = GanProtocol::GEN2;
    std::unique_ptr<GanDriver> driver_;
    std::unique_ptr<GanEncrypter> enc_;
    std::string name_;
    QueueHandle_t queue_ = nullptr;
    std::mutex outMx_;
    std::vector<Bytes> outgoing_;
    bool wantDisconnect_ = false;
};

/** A connected GAN Robot. Its calls block, so run them off the main loop. */
class RobotClient {
public:
    static std::unique_ptr<RobotClient> attach(NimBLEClient* client, const std::string& name);
    ~RobotClient();

    /**
     * Send moves and wait until the robot has finished turning.
     * [progress] gets (writes done, total writes). Returns false on a BLE error or timeout.
     */
    bool scramble(const Moves& moves, const std::function<void(int, int)>& progress);
    void disconnect();
    bool connected() const;
    const std::string& name() const { return name_; }
    NimBLEAddress address() const;

private:
    RobotClient() = default;
    bool awaitIdle();
    NimBLEClient* client_ = nullptr;
    NimBLERemoteCharacteristic* scramble_ = nullptr;
    NimBLERemoteCharacteristic* status_ = nullptr;
    std::string name_;
};

}  // namespace og
