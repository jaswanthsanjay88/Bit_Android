#pragma once

#include <cstdint>
#include <array>
#include <vector>
#include <string>
#include "neuron_header.h"

namespace neuron::packet {

constexpr size_t ACL_ENTRY_SIZE = 256;
constexpr size_t BCRYPT_HASH_SIZE = 64;
constexpr size_t DEK_SALT_SIZE = 16;
constexpr size_t ENCRYPTED_DEK_USER_SIZE = 60;  // IV(12) + DEK(32) + Tag(16)
constexpr size_t USER_LABEL_SIZE = 48;

enum Permission : uint8_t {
    PERMISSION_READ = 0x01,
    PERMISSION_WRITE = 0x02,
    PERMISSION_ADMIN = 0x04,
    PERMISSION_READ_WRITE_ADMIN = 0x07
};

struct ACLEntry {
    uint8_t slotId = 0;
    uint8_t permissions = PERMISSION_READ;
    std::array<char, BCRYPT_HASH_SIZE> passwordHash{};
    std::array<uint8_t, DEK_SALT_SIZE> salt{};
    std::array<uint8_t, ENCRYPTED_DEK_USER_SIZE> encryptedDek{};
    uint64_t createdTimestamp = 0;
    uint64_t modifiedTimestamp = 0;
    std::string userLabel;

    bool hasRead() const { return permissions & PERMISSION_READ; }
    bool hasWrite() const { return permissions & PERMISSION_WRITE; }
    bool hasAdmin() const { return permissions & PERMISSION_ADMIN; }
    bool isAdmin() const { return permissions == PERMISSION_READ_WRITE_ADMIN; }

    std::vector<uint8_t> toBytes() const;
    static ACLEntry fromBytes(const std::vector<uint8_t>& data);
    static ACLEntry fromBytes(const uint8_t* data, size_t size);
};

class ACLManager {
public:
    void addEntry(const ACLEntry& entry);
    void removeEntry(uint8_t slotId);
    ACLEntry* getEntry(uint8_t slotId);
    const ACLEntry* getEntry(uint8_t slotId) const;
    ACLEntry* findByPassword(const std::string& password);
    size_t count() const { return entries_.size(); }
    bool isFull() const { return entries_.size() >= maxUsers_; }

    std::vector<uint8_t> toBytes() const;
    static ACLManager fromBytes(const uint8_t* data, size_t size, uint8_t userCount);

    const std::vector<ACLEntry>& entries() const { return entries_; }
    void setMaxUsers(uint16_t max) { maxUsers_ = max; }

private:
    std::vector<ACLEntry> entries_;
    uint16_t maxUsers_ = 20;
};

} // namespace neuron::packet