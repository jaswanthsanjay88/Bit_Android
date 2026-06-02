#include "sha256.h"
#include <openssl/sha.h>
#include <openssl/rand.h>

namespace neuron::crypto {

std::vector<uint8_t> generateRandomBytes(size_t count) {
    std::vector<uint8_t> bytes(count);
    RAND_bytes(bytes.data(), static_cast<int>(count));
    return bytes;
}

std::string bytesToHex(const std::vector<uint8_t>& bytes) {
    static const char hex[] = "0123456789abcdef";
    std::string result;
    result.reserve(bytes.size() * 2);
    for (uint8_t b : bytes) {
        result.push_back(hex[b >> 4]);
        result.push_back(hex[b & 0x0F]);
    }
    return result;
}

std::vector<uint8_t> hexToBytes(const std::string& hex) {
    std::vector<uint8_t> bytes;
    bytes.reserve(hex.size() / 2);
    for (size_t i = 0; i < hex.size(); i += 2) {
        uint8_t b = 0;
        for (int j = 0; j < 2; j++) {
            char c = hex[i + j];
            b <<= 4;
            if (c >= '0' && c <= '9') b |= c - '0';
            else if (c >= 'a' && c <= 'f') b |= c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') b |= c - 'A' + 10;
        }
        bytes.push_back(b);
    }
    return bytes;
}

EncryptedData EncryptedData::fromBytes(const std::vector<uint8_t>& data) {
    EncryptedData result;
    if (data.size() < AES_IV_SIZE + AES_TAG_SIZE) {
        return result;
    }
    result.iv.assign(data.begin(), data.begin() + AES_IV_SIZE);
    result.tag.assign(data.end() - AES_TAG_SIZE, data.end());
    result.ciphertext.assign(data.begin() + AES_IV_SIZE, data.end() - AES_TAG_SIZE);
    return result;
}

std::vector<uint8_t> EncryptedData::toBytes() const {
    std::vector<uint8_t> result;
    result.reserve(iv.size() + ciphertext.size() + tag.size());
    result.insert(result.end(), iv.begin(), iv.end());
    result.insert(result.end(), ciphertext.begin(), ciphertext.end());
    result.insert(result.end(), tag.begin(), tag.end());
    return result;
}

std::array<uint8_t, SHA256_SIZE> sha256(const uint8_t* data, size_t size) {
    std::array<uint8_t, SHA256_SIZE> hash;
    SHA256(data, size, hash.data());
    return hash;
}

std::array<uint8_t, SHA256_SIZE> sha256(const std::vector<uint8_t>& data) {
    return sha256(data.data(), data.size());
}

std::array<uint8_t, SHA256_SIZE> sha256(const std::string& str) {
    return sha256(reinterpret_cast<const uint8_t*>(str.data()), str.size());
}

} // namespace neuron::crypto