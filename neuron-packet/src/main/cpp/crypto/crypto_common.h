#pragma once

#include <vector>
#include <cstdint>
#include <array>
#include <string>

namespace neuron::crypto {

constexpr size_t AES_KEY_SIZE = 32;
constexpr size_t AES_IV_SIZE = 12;
constexpr size_t AES_TAG_SIZE = 16;
constexpr size_t SHA256_SIZE = 32;
constexpr size_t SALT_SIZE = 16;
constexpr size_t BCRYPT_HASH_SIZE = 64;
constexpr int PBKDF2_ITERATIONS = 100000;
constexpr int BCRYPT_COST = 12;

struct EncryptedData {
    std::vector<uint8_t> iv;
    std::vector<uint8_t> ciphertext;
    std::vector<uint8_t> tag;

    std::vector<uint8_t> toBytes() const;
    static EncryptedData fromBytes(const std::vector<uint8_t>& data);
};

std::vector<uint8_t> generateRandomBytes(size_t count);
std::string bytesToHex(const std::vector<uint8_t>& bytes);
std::vector<uint8_t> hexToBytes(const std::string& hex);

} // namespace neuron::crypto