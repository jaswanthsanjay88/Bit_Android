#pragma once

#include "crypto_common.h"

namespace neuron::crypto {

std::vector<uint8_t> pbkdf2(
    const std::string& password,
    const std::vector<uint8_t>& salt,
    int iterations = PBKDF2_ITERATIONS,
    size_t keyLength = AES_KEY_SIZE
);

struct DerivedKey {
    std::vector<uint8_t> key;
    std::vector<uint8_t> salt;
};

DerivedKey deriveKey(const std::string& password, const std::vector<uint8_t>* existingSalt = nullptr);

} // namespace neuron::crypto