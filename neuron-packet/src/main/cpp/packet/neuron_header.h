#pragma once

#include <cstdint>
#include <array>
#include <vector>
#include <string>
#include "utils/byte_buffer.h"

namespace neuron::packet {

constexpr size_t HEADER_SIZE = 512;
constexpr size_t MAGIC_SIZE = 4;
constexpr size_t UUID_SIZE = 16;
constexpr size_t RECOVERY_HASH_SIZE = 32;
constexpr size_t ENCRYPTED_DEK_SIZE = 60;  // IV(12) + DEK(32) + Tag(16)
constexpr size_t METADATA_JSON_SIZE = 386;

constexpr char MAGIC[5] = "NRON";
constexpr uint16_t VERSION = 1;

enum class LoadingMode : uint8_t {
    TRANSIENT = 0,
    EMBEDDED = 1
};

struct NeuronHeader {
    std::array<char, MAGIC_SIZE> magic = {'N', 'R', 'O', 'N'};
    uint16_t version = VERSION;
    std::array<uint8_t, UUID_SIZE> packetId{};
    uint64_t createdTimestamp = 0;
    std::array<uint8_t, RECOVERY_HASH_SIZE> recoveryKeyHash{};
    LoadingMode loadingMode = LoadingMode::EMBEDDED;
    uint8_t userCount = 0;
    uint16_t maxUsers = 20;
    std::array<uint8_t, ENCRYPTED_DEK_SIZE> recoveryEncryptedDek{};
    std::string metadataJson;

    bool isValid() const;
    std::vector<uint8_t> toBytes() const;
    static NeuronHeader fromBytes(const std::vector<uint8_t>& data);
    static NeuronHeader fromBytes(const uint8_t* data, size_t size);
};

std::array<uint8_t, UUID_SIZE> generateUUID();
std::string uuidToString(const std::array<uint8_t, UUID_SIZE>& uuid);
std::array<uint8_t, UUID_SIZE> stringToUUID(const std::string& str);

} // namespace neuron::packet