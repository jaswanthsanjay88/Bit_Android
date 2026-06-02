#include "acl_entry.h"
#include "crypto/bcrypt.h"
#include "utils/byte_buffer.h"
#include <cstring>

namespace neuron::packet {

std::vector<uint8_t> ACLEntry::toBytes() const {
    ByteBuffer buffer(ACL_ENTRY_SIZE);

    buffer.writeByte(slotId);
    buffer.writeByte(permissions);
    buffer.writeBytes(reinterpret_cast<const uint8_t*>(passwordHash.data()), BCRYPT_HASH_SIZE);
    buffer.writeBytes(salt.data(), DEK_SALT_SIZE);
    buffer.writeBytes(encryptedDek.data(), ENCRYPTED_DEK_USER_SIZE);
    buffer.writeLong(createdTimestamp);
    buffer.writeLong(modifiedTimestamp);
    buffer.writeString(userLabel, USER_LABEL_SIZE);

    auto& data = buffer.buffer();
    data.resize(ACL_ENTRY_SIZE, 0);
    return data;
}

ACLEntry ACLEntry::fromBytes(const std::vector<uint8_t>& data) {
    return fromBytes(data.data(), data.size());
}

ACLEntry ACLEntry::fromBytes(const uint8_t* data, size_t size) {
    if (size < ACL_ENTRY_SIZE) {
        throw std::runtime_error("Invalid ACL entry size");
    }

    ByteBuffer buffer(data, size);
    ACLEntry entry;

    entry.slotId = buffer.readByte();
    entry.permissions = buffer.readByte();

    auto hashBytes = buffer.readBytes(BCRYPT_HASH_SIZE);
    std::memcpy(entry.passwordHash.data(), hashBytes.data(), BCRYPT_HASH_SIZE);

    auto saltBytes = buffer.readBytes(DEK_SALT_SIZE);
    std::memcpy(entry.salt.data(), saltBytes.data(), DEK_SALT_SIZE);

    auto dekBytes = buffer.readBytes(ENCRYPTED_DEK_USER_SIZE);
    std::memcpy(entry.encryptedDek.data(), dekBytes.data(), ENCRYPTED_DEK_USER_SIZE);

    entry.createdTimestamp = buffer.readLong();
    entry.modifiedTimestamp = buffer.readLong();
    entry.userLabel = buffer.readString(USER_LABEL_SIZE);

    return entry;
}

void ACLManager::addEntry(const ACLEntry& entry) {
    if (isFull()) throw std::runtime_error("ACL is full");
    entries_.push_back(entry);
}

void ACLManager::removeEntry(uint8_t slotId) {
    entries_.erase(
        std::remove_if(entries_.begin(), entries_.end(),
            [slotId](const ACLEntry& e) { return e.slotId == slotId; }),
        entries_.end()
    );
}

ACLEntry* ACLManager::getEntry(uint8_t slotId) {
    for (auto& entry : entries_) {
        if (entry.slotId == slotId) return &entry;
    }
    return nullptr;
}

const ACLEntry* ACLManager::getEntry(uint8_t slotId) const {
    for (const auto& entry : entries_) {
        if (entry.slotId == slotId) return &entry;
    }
    return nullptr;
}

ACLEntry* ACLManager::findByPassword(const std::string& password) {
    for (auto& entry : entries_) {
        std::string storedHash(entry.passwordHash.data(), BCRYPT_HASH_SIZE);
        storedHash = storedHash.c_str();
        if (crypto::bcryptVerify(password, storedHash)) {
            return &entry;
        }
    }
    return nullptr;
}

std::vector<uint8_t> ACLManager::toBytes() const {
    std::vector<uint8_t> result;
    result.reserve(entries_.size() * ACL_ENTRY_SIZE);
    for (const auto& entry : entries_) {
        auto bytes = entry.toBytes();
        result.insert(result.end(), bytes.begin(), bytes.end());
    }
    return result;
}

ACLManager ACLManager::fromBytes(const uint8_t* data, size_t size, uint8_t userCount) {
    ACLManager manager;
    for (uint8_t i = 0; i < userCount; i++) {
        size_t offset = i * ACL_ENTRY_SIZE;
        if (offset + ACL_ENTRY_SIZE > size) break;
        manager.addEntry(ACLEntry::fromBytes(data + offset, ACL_ENTRY_SIZE));
    }
    return manager;
}

} // namespace neuron::packet