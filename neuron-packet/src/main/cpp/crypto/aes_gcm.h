#pragma once

#include "crypto_common.h"

namespace neuron::crypto {

EncryptedData aesGcmEncrypt(
    const std::vector<uint8_t>& plaintext,
    const std::vector<uint8_t>& key,
    const std::vector<uint8_t>* aad = nullptr
);

std::vector<uint8_t> aesGcmDecrypt(
    const EncryptedData& encrypted,
    const std::vector<uint8_t>& key,
    const std::vector<uint8_t>* aad = nullptr
);

std::vector<uint8_t> aesGcmDecrypt(
    const std::vector<uint8_t>& data,
    const std::vector<uint8_t>& key,
    const std::vector<uint8_t>* aad = nullptr
);

} // namespace neuron::crypto