#include "neuron_header.h"
#include "crypto/sha256.h"
#include <cstring>
#include <sstream>
#include <iomanip>

namespace neuron::packet {

bool NeuronHeader::isValid() const {
    return magic[0] == 'N' && magic[1] == 'R' && magic[2] == 'O' && magic[3] == 'N' && version == VERSION;
}

std::vector<uint8_t> NeuronHeader::toBytes() const {
    ByteBuffer buffer(HEADER_SIZE);

    buffer.writeBytes(reinterpret_cast<const uint8_t*>(magic.data()), MAGIC_SIZE);
    buffer.writeShort(version);
    buffer.writeBytes(packetId.data(), UUID_SIZE);
    buffer.writeLong(createdTimestamp);
    buffer.writeBytes(recoveryKeyHash.data(), RECOVERY_HASH_SIZE);
    buffer.writeByte(static_cast<uint8_t>(loadingMode));
    buffer.writeByte(userCount);
    buffer.writeShort(maxUsers);
    buffer.writeBytes(recoveryEncryptedDek.data(), ENCRYPTED_DEK_SIZE);
    buffer.writeString(metadataJson, METADATA_JSON_SIZE);

    auto& data = buffer.buffer();
    data.resize(HEADER_SIZE, 0);
    return data;
}

NeuronHeader NeuronHeader::fromBytes(const std::vector<uint8_t>& data) {
    return fromBytes(data.data(), data.size());
}

NeuronHeader NeuronHeader::fromBytes(const uint8_t* data, size_t size) {
    if (size < HEADER_SIZE) {
        throw std::runtime_error("Invalid header size");
    }

    ByteBuffer buffer(data, size);
    NeuronHeader header;

    auto magicBytes = buffer.readBytes(MAGIC_SIZE);
    std::memcpy(header.magic.data(), magicBytes.data(), MAGIC_SIZE);

    header.version = buffer.readShort();

    auto packetIdBytes = buffer.readBytes(UUID_SIZE);
    std::memcpy(header.packetId.data(), packetIdBytes.data(), UUID_SIZE);

    header.createdTimestamp = buffer.readLong();

    auto hashBytes = buffer.readBytes(RECOVERY_HASH_SIZE);
    std::memcpy(header.recoveryKeyHash.data(), hashBytes.data(), RECOVERY_HASH_SIZE);

    header.loadingMode = static_cast<LoadingMode>(buffer.readByte());
    header.userCount = buffer.readByte();
    header.maxUsers = buffer.readShort();

    auto dekBytes = buffer.readBytes(ENCRYPTED_DEK_SIZE);
    std::memcpy(header.recoveryEncryptedDek.data(), dekBytes.data(), ENCRYPTED_DEK_SIZE);

    header.metadataJson = buffer.readString(METADATA_JSON_SIZE);

    return header;
}

std::array<uint8_t, UUID_SIZE> generateUUID() {
    auto randomBytes = crypto::generateRandomBytes(UUID_SIZE);
    std::array<uint8_t, UUID_SIZE> uuid;
    std::memcpy(uuid.data(), randomBytes.data(), UUID_SIZE);
    uuid[6] = (uuid[6] & 0x0F) | 0x40;
    uuid[8] = (uuid[8] & 0x3F) | 0x80;
    return uuid;
}

std::string uuidToString(const std::array<uint8_t, UUID_SIZE>& uuid) {
    std::ostringstream ss;
    ss << std::hex << std::setfill('0');
    for (size_t i = 0; i < UUID_SIZE; i++) {
        if (i == 4 || i == 6 || i == 8 || i == 10) ss << '-';
        ss << std::setw(2) << static_cast<int>(uuid[i]);
    }
    return ss.str();
}

std::array<uint8_t, UUID_SIZE> stringToUUID(const std::string& str) {
    std::array<uint8_t, UUID_SIZE> uuid{};
    size_t idx = 0;
    for (size_t i = 0; i < str.size() && idx < UUID_SIZE; i += 2) {
        if (str[i] == '-') { i++; }
        if (i + 1 >= str.size()) break;
        char hex[3] = {str[i], str[i + 1], 0};
        uuid[idx++] = static_cast<uint8_t>(std::strtol(hex, nullptr, 16));
    }
    return uuid;
}

} // namespace neuron::packet