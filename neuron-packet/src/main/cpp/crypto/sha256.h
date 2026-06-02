#pragma once

#include "crypto_common.h"

namespace neuron::crypto {

std::array<uint8_t, SHA256_SIZE> sha256(const uint8_t* data, size_t size);
std::array<uint8_t, SHA256_SIZE> sha256(const std::vector<uint8_t>& data);
std::array<uint8_t, SHA256_SIZE> sha256(const std::string& str);

} // namespace neuron::crypto