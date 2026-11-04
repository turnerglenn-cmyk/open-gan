// Minimal AES-128 block cipher (FIPS-197), no dependencies, so the core builds
// and tests on a PC as well as on the ESP32.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>

namespace og {

class Aes128 {
public:
    explicit Aes128(const uint8_t key[16]);
    void encryptBlock(uint8_t block[16]) const;
    void decryptBlock(uint8_t block[16]) const;

private:
    uint8_t roundKeys_[176];
};

}  // namespace og
