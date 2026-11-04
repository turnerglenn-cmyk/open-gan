// SPDX-License-Identifier: GPL-3.0-or-later
#include "ble.h"

#include <Arduino.h>

#include <algorithm>
#include <cstring>

namespace og {
namespace ble {

void begin() {
    NimBLEDevice::init("Open GAN");
    NimBLEDevice::setPower(9);  // dBm; robots and cubes are close by
    NimBLEScan* scan = NimBLEDevice::getScan();
    scan->setActiveScan(true);
    scan->setInterval(100);
    scan->setWindow(99);
}

std::string addressString(const NimBLEAddress& a) {
    std::string s = a.toString();
    for (char& c : s) c = char(toupper((unsigned char)c));
    return s;
}

std::vector<ScannedDevice> scan(uint32_t ms) {
    NimBLEScan* scan = NimBLEDevice::getScan();
    NimBLEScanResults results = scan->getResults(ms, false);
    std::vector<ScannedDevice> found;
    NimBLEUUID robotService(robot::SERVICE);
    for (int i = 0; i < results.getCount(); i++) {
        const NimBLEAdvertisedDevice* d = results.getDevice(i);
        if (!d || !d->haveName()) continue;
        std::string name = d->getName();
        if (!gan::isGanName(name)) continue;
        ScannedDevice s;
        s.name = name;
        s.address = d->getAddress();
        s.rssi = d->getRSSI();
        s.looksLikeRobot = d->isAdvertisingService(robotService);
        // Manufacturer data starts with the 2-byte company id (0x??01); the rest holds the MAC.
        for (uint8_t k = 0; k < d->getManufacturerDataCount(); k++) {
            std::string md = d->getManufacturerData(k);
            if (md.size() >= 8 && uint8_t(md[0]) == 0x01) {
                s.advertisedMac = gan::macFromManufacturerData(reinterpret_cast<const uint8_t*>(md.data()) + 2, md.size() - 2);
                break;
            }
        }
        found.push_back(s);
    }
    scan->clearResults();
    std::sort(found.begin(), found.end(), [](const ScannedDevice& a, const ScannedDevice& b) { return a.rssi > b.rssi; });
    return found;
}

NimBLEClient* connect(const NimBLEAddress& address) {
    NimBLEClient* c = NimBLEDevice::createClient();
    if (!c) return nullptr;
    c->setConnectTimeout(8000);
    if (!c->connect(address)) {
        NimBLEDevice::deleteClient(c);
        return nullptr;
    }
    return c;
}

void release(NimBLEClient* c) {
    if (!c) return;
    if (c->isConnected()) c->disconnect();
    NimBLEDevice::deleteClient(c);
}

}  // namespace ble

// ---------------------------------------------------------------- cube

std::unique_ptr<CubeClient> CubeClient::attach(NimBLEClient* client, const std::string& name, const std::string& mac) {
    struct Ids {
        GanProtocol p;
        const char *service, *command, *state;
    };
    const Ids ids[] = {
        {GanProtocol::GEN2, gan::GEN2_SERVICE, gan::GEN2_COMMAND, gan::GEN2_STATE},
        {GanProtocol::GEN3, gan::GEN3_SERVICE, gan::GEN3_COMMAND, gan::GEN3_STATE},
        {GanProtocol::GEN4, gan::GEN4_SERVICE, gan::GEN4_COMMAND, gan::GEN4_STATE},
    };
    for (const Ids& id : ids) {
        NimBLERemoteService* svc = client->getService(NimBLEUUID(id.service));
        if (!svc) continue;
        NimBLERemoteCharacteristic* cmd = svc->getCharacteristic(NimBLEUUID(id.command));
        NimBLERemoteCharacteristic* st = svc->getCharacteristic(NimBLEUUID(id.state));
        if (!cmd || !st) return nullptr;
        uint8_t salt[6];
        if (!gan::saltFromMac(mac, salt)) return nullptr;

        std::unique_ptr<CubeClient> c(new CubeClient());
        c->client_ = client;
        c->command_ = cmd;
        c->state_ = st;
        c->protocol_ = id.p;
        c->name_ = name;
        c->driver_ = makeDriver(id.p);
        c->enc_.reset(new GanEncrypter(name.rfind("AiCube", 0) == 0 ? gan::KEY_MOYU_AI : gan::KEY_GAN, salt));
        c->queue_ = xQueueCreate(64, sizeof(RawNotification));
        QueueHandle_t q = c->queue_;
        bool ok = st->subscribe(true, [q](NimBLERemoteCharacteristic*, uint8_t* data, size_t len, bool) {
            RawNotification n;
            n.len = uint8_t(std::min<size_t>(len, sizeof n.data));
            memcpy(n.data, data, n.len);
            n.at = millis();
            xQueueSend(q, &n, 0);
        });
        if (!ok) {
            c->client_ = nullptr;  // caller still owns the client
            return nullptr;
        }
        c->send(CubeCommand::REQUEST_HARDWARE);
        c->send(CubeCommand::REQUEST_FACELETS);
        c->send(CubeCommand::REQUEST_BATTERY);
        return c;
    }
    return nullptr;
}

CubeClient::~CubeClient() {
    ble::release(client_);
    if (queue_) vQueueDelete(queue_);
}

NimBLEAddress CubeClient::address() const { return client_->getPeerAddress(); }

bool CubeClient::connected() const { return client_ && client_->isConnected() && !wantDisconnect_; }

void CubeClient::send(CubeCommand c) {
    std::lock_guard<std::mutex> g(outMx_);
    outgoing_.push_back(driver_->createCommand(c));
}

void CubeClient::sendCommand(const Bytes& plain) {
    std::lock_guard<std::mutex> g(outMx_);
    outgoing_.push_back(plain);
}

void CubeClient::disconnect() { wantDisconnect_ = true; }

void CubeClient::poll(CubeEvents& out) {
    if (wantDisconnect_) {
        if (client_->isConnected()) client_->disconnect();
        return;
    }
    RawNotification n;
    while (xQueueReceive(queue_, &n, 0) == pdTRUE) {
        if (n.len < 16) continue;
        Bytes plain = enc_->decrypt(Bytes(n.data, n.data + n.len));
        driver_->handle(*this, plain, n.at, out);
    }
    std::vector<Bytes> todo;
    {
        std::lock_guard<std::mutex> g(outMx_);
        todo.swap(outgoing_);
    }
    for (const Bytes& b : todo) {
        Bytes e = enc_->encrypt(b);
        command_->writeValue(e.data(), e.size(), true);
    }
}

// ---------------------------------------------------------------- robot

std::unique_ptr<RobotClient> RobotClient::attach(NimBLEClient* client, const std::string& name) {
    NimBLERemoteService* svc = client->getService(NimBLEUUID(robot::SERVICE));
    if (!svc) return nullptr;
    NimBLERemoteService* info = client->getService(NimBLEUUID(robot::DEVICE_INFO_SERVICE));
    if (!info) return nullptr;
    NimBLERemoteCharacteristic* model = info->getCharacteristic(NimBLEUUID(robot::MODEL_NUMBER_CHAR));
    if (!model) return nullptr;
    NimBLEAttValue v = model->readValue();
    std::string text(reinterpret_cast<const char*>(v.data()), v.size());
    if (!robot::isRobotModel(text)) return nullptr;
    std::unique_ptr<RobotClient> r(new RobotClient());
    r->client_ = client;
    r->scramble_ = svc->getCharacteristic(NimBLEUUID(robot::SCRAMBLE_CHAR));
    r->status_ = svc->getCharacteristic(NimBLEUUID(robot::STATUS_CHAR));
    r->name_ = name;
    if (!r->scramble_ || !r->status_) {
        r->client_ = nullptr;  // caller still owns the client
        return nullptr;
    }
    return r;
}

RobotClient::~RobotClient() { ble::release(client_); }

NimBLEAddress RobotClient::address() const { return client_->getPeerAddress(); }

bool RobotClient::connected() const { return client_ && client_->isConnected(); }

void RobotClient::disconnect() {
    if (client_ && client_->isConnected()) client_->disconnect();
}

bool RobotClient::scramble(const Moves& moves, const std::function<void(int, int)>& progress) {
    std::vector<Bytes> chunks = robot::encode(moves);
    int total = int(chunks.size());
    for (int i = 0; i < total; i++) {
        if (progress) progress(i, total);
        if (!connected()) return false;
        if (!scramble_->writeValue(chunks[i].data(), chunks[i].size(), true)) return false;
        if (!awaitIdle()) return false;
    }
    if (progress) progress(total, total);
    return true;
}

/** Status reads non-zero while turning and 0 when idle. */
bool RobotClient::awaitIdle() {
    bool sawBusy = false;
    uint32_t start = millis();
    while (millis() - start < 120000) {
        if (!connected()) return false;
        NimBLEAttValue v = status_->readValue();
        bool busy = v.size() > 0 && v.data()[0] != 0;
        if (busy) sawBusy = true;
        if (sawBusy && !busy) return true;
        // If the robot never reports busy (e.g. a no-op write), don't wait forever.
        if (!sawBusy && millis() - start > 3000) return true;
        delay(10);
    }
    return false;
}

}  // namespace og
