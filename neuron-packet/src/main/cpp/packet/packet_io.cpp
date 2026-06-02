#include "packet_io.h"
#include "crypto/aes_gcm.h"
#include "crypto/pbkdf2.h"
#include "crypto/bcrypt.h"
#include "crypto/sha256.h"
#include "utils/byte_buffer.h"
#include <lz4.h>
#include <fstream>
#include <cstring>

namespace neuron::packet {

std::vector<uint8_t> NeuronPacketIO::createMasterDek() {
    return crypto::generateRandomBytes(crypto::AES_KEY_SIZE);
}

std::vector<uint8_t> NeuronPacketIO::encryptDekForUser(
    const std::vector<uint8_t>& dek,
    const std::string& password,
    std::vector<uint8_t>& outSalt
) {
    auto derived = crypto::deriveKey(password);
    outSalt = derived.salt;
    auto encrypted = crypto::aesGcmEncrypt(dek, derived.key);
    return encrypted.toBytes();
}

std::vector<uint8_t> NeuronPacketIO::decryptDekForUser(const ACLEntry& entry, const std::string& password) {
    std::vector<uint8_t> salt(entry.salt.begin(), entry.salt.end());
    auto derivedKey = crypto::pbkdf2(password, salt);

    std::vector<uint8_t> encDek(entry.encryptedDek.begin(), entry.encryptedDek.end());
    return crypto::aesGcmDecrypt(encDek, derivedKey);
}

std::vector<uint8_t> NeuronPacketIO::compressData(const std::vector<uint8_t>& data) {
    int maxSize = LZ4_compressBound(static_cast<int>(data.size()));
    std::vector<uint8_t> compressed(maxSize + 4);

    ByteBuffer buf;
    buf.writeInt(static_cast<uint32_t>(data.size()));

    int compressedSize = LZ4_compress_default(
        reinterpret_cast<const char*>(data.data()),
        reinterpret_cast<char*>(compressed.data() + 4),
        static_cast<int>(data.size()),
        maxSize
    );

    if (compressedSize <= 0) throw std::runtime_error("Compression failed");

    std::memcpy(compressed.data(), buf.data(), 4);
    compressed.resize(compressedSize + 4);
    return compressed;
}

std::vector<uint8_t> NeuronPacketIO::decompressData(const std::vector<uint8_t>& data, size_t originalSize) {
    std::vector<uint8_t> decompressed(originalSize);

    int result = LZ4_decompress_safe(
        reinterpret_cast<const char*>(data.data()),
        reinterpret_cast<char*>(decompressed.data()),
        static_cast<int>(data.size()),
        static_cast<int>(originalSize)
    );

    if (result < 0) throw std::runtime_error("Decompression failed");
    decompressed.resize(result);
    return decompressed;
}

std::vector<uint8_t> NeuronPacketIO::encryptPayload(
    const std::vector<uint8_t>& plaintext,
    const std::vector<uint8_t>& dek,
    bool compress
) {
    std::vector<uint8_t> toEncrypt = compress ? compressData(plaintext) : plaintext;
    auto encrypted = crypto::aesGcmEncrypt(toEncrypt, dek);
    return encrypted.toBytes();
}

ExportResult NeuronPacketIO::exportPacket(
    const std::string& outputPath,
    const PacketMetadata& metadata,
    const std::vector<uint8_t>& payload,
    const ExportConfig& config,
    ProgressCallback progress
) {
    ExportResult result;

    try {
        if (progress) progress(0.1f);

        auto masterDek = createMasterDek();
        auto recoveryKeyUuid = generateUUID();
        std::string recoveryKey = uuidToString(recoveryKeyUuid);

        header_ = NeuronHeader();
        header_.packetId = generateUUID();
        header_.createdTimestamp = static_cast<uint64_t>(std::time(nullptr)) * 1000;
        header_.loadingMode = config.loadingMode;
        header_.userCount = 1 + static_cast<uint8_t>(config.readOnlyUsers.size());
        header_.metadataJson = "{\"name\":\"" + metadata.name + "\",\"domain\":\"" + metadata.domain + "\"}";

        auto recoveryHash = crypto::sha256(recoveryKey);
        std::memcpy(header_.recoveryKeyHash.data(), recoveryHash.data(), RECOVERY_HASH_SIZE);

        std::vector<uint8_t> recSalt;
        auto recoveryEncDek = encryptDekForUser(masterDek, recoveryKey, recSalt);
        std::memcpy(header_.recoveryEncryptedDek.data(), recoveryEncDek.data(),
                    std::min(recoveryEncDek.size(), static_cast<size_t>(ENCRYPTED_DEK_SIZE)));

        if (progress) progress(0.3f);

        acl_ = ACLManager();
        acl_.setMaxUsers(header_.maxUsers);

        ACLEntry adminEntry;
        adminEntry.slotId = 0;
        adminEntry.permissions = PERMISSION_READ_WRITE_ADMIN;
        adminEntry.createdTimestamp = header_.createdTimestamp;
        adminEntry.modifiedTimestamp = header_.createdTimestamp;
        adminEntry.userLabel = "Administrator";

        std::string adminHash = crypto::bcryptHash(config.adminPassword);
        std::memcpy(adminEntry.passwordHash.data(), adminHash.c_str(),
                    std::min(adminHash.size(), static_cast<size_t>(BCRYPT_HASH_SIZE)));

        std::vector<uint8_t> adminSalt;
        auto adminEncDek = encryptDekForUser(masterDek, config.adminPassword, adminSalt);
        std::memcpy(adminEntry.salt.data(), adminSalt.data(), DEK_SALT_SIZE);
        std::memcpy(adminEntry.encryptedDek.data(), adminEncDek.data(),
                    std::min(adminEncDek.size(), static_cast<size_t>(ENCRYPTED_DEK_USER_SIZE)));

        acl_.addEntry(adminEntry);

        if (progress) progress(0.4f);

        uint8_t slot = 1;
        for (const auto& user : config.readOnlyUsers) {
            ACLEntry entry;
            entry.slotId = slot++;
            entry.permissions = user.permissions;
            entry.createdTimestamp = header_.createdTimestamp;
            entry.modifiedTimestamp = header_.createdTimestamp;
            entry.userLabel = user.label;

            std::string hash = crypto::bcryptHash(user.password);
            std::memcpy(entry.passwordHash.data(), hash.c_str(),
                        std::min(hash.size(), static_cast<size_t>(BCRYPT_HASH_SIZE)));

            std::vector<uint8_t> salt;
            auto encDek = encryptDekForUser(masterDek, user.password, salt);
            std::memcpy(entry.salt.data(), salt.data(), DEK_SALT_SIZE);
            std::memcpy(entry.encryptedDek.data(), encDek.data(),
                        std::min(encDek.size(), static_cast<size_t>(ENCRYPTED_DEK_USER_SIZE)));

            acl_.addEntry(entry);
        }

        if (progress) progress(0.6f);

        auto encryptedPayload = encryptPayload(payload, masterDek, config.compress);

        if (progress) progress(0.8f);

        std::ofstream file(outputPath, std::ios::binary);
        if (!file) throw std::runtime_error("Cannot create output file");

        auto headerBytes = header_.toBytes();
        file.write(reinterpret_cast<const char*>(headerBytes.data()), headerBytes.size());

        auto aclBytes = acl_.toBytes();
        file.write(reinterpret_cast<const char*>(aclBytes.data()), aclBytes.size());

        file.write(reinterpret_cast<const char*>(encryptedPayload.data()), encryptedPayload.size());

        file.close();

        if (progress) progress(1.0f);

        result.success = true;
        result.packetId = uuidToString(header_.packetId);
        result.recoveryKey = recoveryKey;

    } catch (const std::exception& e) {
        result.success = false;
        result.errorMessage = e.what();
    }

    return result;
}

ImportResult NeuronPacketIO::openPacket(const std::string& packetPath) {
    ImportResult result;

    try {
        std::ifstream file(packetPath, std::ios::binary | std::ios::ate);
        if (!file) throw std::runtime_error("Cannot open packet file");

        auto fileSize = file.tellg();
        file.seekg(0);

        std::vector<uint8_t> headerData(HEADER_SIZE);
        file.read(reinterpret_cast<char*>(headerData.data()), HEADER_SIZE);

        header_ = NeuronHeader::fromBytes(headerData);
        if (!header_.isValid()) throw std::runtime_error("Invalid packet format");

        size_t aclSize = header_.userCount * ACL_ENTRY_SIZE;
        std::vector<uint8_t> aclData(aclSize);
        file.read(reinterpret_cast<char*>(aclData.data()), aclSize);

        acl_ = ACLManager::fromBytes(aclData.data(), aclData.size(), header_.userCount);
        acl_.setMaxUsers(header_.maxUsers);

        size_t payloadSize = static_cast<size_t>(fileSize) - HEADER_SIZE - aclSize;
        encryptedPayload_.resize(payloadSize);
        file.read(reinterpret_cast<char*>(encryptedPayload_.data()), payloadSize);

        currentPath_ = packetPath;
        isOpen_ = true;

        result.success = true;
        result.packetId = uuidToString(header_.packetId);
        result.metadata.packetId = result.packetId;
        result.metadata.loadingMode = header_.loadingMode;

    } catch (const std::exception& e) {
        result.success = false;
        result.errorMessage = e.what();
    }

    return result;
}

AuthResult NeuronPacketIO::authenticate(const std::string& password) {
    AuthResult result;

    if (!isOpen_) {
        result.errorMessage = "No packet open";
        return result;
    }

    try {
        ACLEntry* entry = acl_.findByPassword(password);
        if (!entry) {
            result.errorMessage = "Authentication failed";
            return result;
        }

        result.decryptedDek = decryptDekForUser(*entry, password);
        result.slotId = entry->slotId;
        result.permissions = entry->permissions;
        result.success = true;
        masterDek_ = result.decryptedDek;

    } catch (const std::exception& e) {
        result.errorMessage = e.what();
    }

    return result;
}

std::vector<uint8_t> NeuronPacketIO::decryptPayload(const std::vector<uint8_t>& dek) {
    if (encryptedPayload_.empty()) throw std::runtime_error("No payload loaded");

    auto decrypted = crypto::aesGcmDecrypt(encryptedPayload_, dek);

    if (decrypted.size() > 4) {
        ByteBuffer buf(decrypted.data(), 4);
        uint32_t originalSize = buf.readInt();
        if (originalSize > 0 && originalSize < 100 * 1024 * 1024) {
            return decompressData(
                std::vector<uint8_t>(decrypted.begin() + 4, decrypted.end()),
                originalSize
            );
        }
    }

    return decrypted;
}

void NeuronPacketIO::closePacket() {
    header_ = NeuronHeader();
    acl_ = ACLManager();
    encryptedPayload_.clear();
    masterDek_.clear();
    currentPath_.clear();
    isOpen_ = false;
}

bool NeuronPacketIO::resetAdminPassword(const std::string& recoveryKey, const std::string& newPassword) {
    if (!isOpen_) return false;

    auto providedHash = crypto::sha256(recoveryKey);
    if (std::memcmp(providedHash.data(), header_.recoveryKeyHash.data(), RECOVERY_HASH_SIZE) != 0) {
        return false;
    }

    std::vector<uint8_t> recEncDek(header_.recoveryEncryptedDek.begin(), header_.recoveryEncryptedDek.end());
    auto dek = crypto::aesGcmDecrypt(recEncDek, crypto::deriveKey(recoveryKey).key);

    ACLEntry* admin = acl_.getEntry(0);
    if (!admin) return false;

    std::string newHash = crypto::bcryptHash(newPassword);
    std::memcpy(admin->passwordHash.data(), newHash.c_str(),
                std::min(newHash.size(), static_cast<size_t>(BCRYPT_HASH_SIZE)));

    std::vector<uint8_t> newSalt;
    auto newEncDek = encryptDekForUser(dek, newPassword, newSalt);
    std::memcpy(admin->salt.data(), newSalt.data(), DEK_SALT_SIZE);
    std::memcpy(admin->encryptedDek.data(), newEncDek.data(),
                std::min(newEncDek.size(), static_cast<size_t>(ENCRYPTED_DEK_USER_SIZE)));

    admin->modifiedTimestamp = static_cast<uint64_t>(std::time(nullptr)) * 1000;

    std::ofstream file(currentPath_, std::ios::binary | std::ios::in | std::ios::out);
    if (!file) return false;

    auto headerBytes = header_.toBytes();
    file.write(reinterpret_cast<const char*>(headerBytes.data()), headerBytes.size());

    auto aclBytes = acl_.toBytes();
    file.write(reinterpret_cast<const char*>(aclBytes.data()), aclBytes.size());

    return true;
}

bool NeuronPacketIO::addUser(const UserCredentials& user, const std::string& adminPassword) {
    if (!isOpen_ || acl_.isFull()) return false;

    auto auth = authenticate(adminPassword);
    if (!auth.success || !(auth.permissions & PERMISSION_ADMIN)) return false;

    ACLEntry entry;
    entry.slotId = static_cast<uint8_t>(acl_.count());
    entry.permissions = user.permissions;
    entry.createdTimestamp = static_cast<uint64_t>(std::time(nullptr)) * 1000;
    entry.modifiedTimestamp = entry.createdTimestamp;
    entry.userLabel = user.label;

    std::string hash = crypto::bcryptHash(user.password);
    std::memcpy(entry.passwordHash.data(), hash.c_str(),
                std::min(hash.size(), static_cast<size_t>(BCRYPT_HASH_SIZE)));

    std::vector<uint8_t> salt;
    auto encDek = encryptDekForUser(auth.decryptedDek, user.password, salt);
    std::memcpy(entry.salt.data(), salt.data(), DEK_SALT_SIZE);
    std::memcpy(entry.encryptedDek.data(), encDek.data(),
                std::min(encDek.size(), static_cast<size_t>(ENCRYPTED_DEK_USER_SIZE)));

    acl_.addEntry(entry);
    header_.userCount = static_cast<uint8_t>(acl_.count());

    std::ofstream file(currentPath_, std::ios::binary | std::ios::in | std::ios::out);
    if (!file) return false;

    auto headerBytes = header_.toBytes();
    file.write(reinterpret_cast<const char*>(headerBytes.data()), headerBytes.size());

    auto aclBytes = acl_.toBytes();
    file.write(reinterpret_cast<const char*>(aclBytes.data()), aclBytes.size());

    return true;
}

bool NeuronPacketIO::removeUser(uint8_t slotId, const std::string& adminPassword) {
    if (!isOpen_ || slotId == 0) return false;

    auto auth = authenticate(adminPassword);
    if (!auth.success || !(auth.permissions & PERMISSION_ADMIN)) return false;

    acl_.removeEntry(slotId);
    header_.userCount = static_cast<uint8_t>(acl_.count());

    std::fstream file(currentPath_, std::ios::binary | std::ios::in | std::ios::out);
    if (!file) return false;

    auto headerBytes = header_.toBytes();
    file.write(reinterpret_cast<const char*>(headerBytes.data()), headerBytes.size());

    auto aclBytes = acl_.toBytes();
    file.write(reinterpret_cast<const char*>(aclBytes.data()), aclBytes.size());

    return true;
}

bool NeuronPacketIO::changePassword(uint8_t slotId, const std::string& oldPassword, const std::string& newPassword) {
    if (!isOpen_) return false;

    auto auth = authenticate(oldPassword);
    if (!auth.success) return false;

    ACLEntry* entry = acl_.getEntry(slotId);
    if (!entry) return false;

    if (auth.slotId != slotId && !(auth.permissions & PERMISSION_ADMIN)) return false;

    std::string newHash = crypto::bcryptHash(newPassword);
    std::memcpy(entry->passwordHash.data(), newHash.c_str(),
                std::min(newHash.size(), static_cast<size_t>(BCRYPT_HASH_SIZE)));

    std::vector<uint8_t> newSalt;
    auto newEncDek = encryptDekForUser(auth.decryptedDek, newPassword, newSalt);
    std::memcpy(entry->salt.data(), newSalt.data(), DEK_SALT_SIZE);
    std::memcpy(entry->encryptedDek.data(), newEncDek.data(),
                std::min(newEncDek.size(), static_cast<size_t>(ENCRYPTED_DEK_USER_SIZE)));

    entry->modifiedTimestamp = static_cast<uint64_t>(std::time(nullptr)) * 1000;

    std::fstream file(currentPath_, std::ios::binary | std::ios::in | std::ios::out);
    if (!file) return false;

    file.seekp(HEADER_SIZE + slotId * ACL_ENTRY_SIZE);
    auto entryBytes = entry->toBytes();
    file.write(reinterpret_cast<const char*>(entryBytes.data()), entryBytes.size());

    return true;
}

} // namespace neuron::packet