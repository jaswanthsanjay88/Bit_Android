#pragma once

#include "neuron_header.h"
#include "acl_entry.h"
#include <string>
#include <functional>

namespace neuron::packet {

struct PacketMetadata {
    std::string packetId;
    std::string name;
    std::string description;
    std::string domain;
    std::string language = "en";
    std::string version;
    std::vector<std::string> tags;
    uint32_t documentCount = 0;
    uint32_t chunkCount = 0;
    uint32_t embeddingCount = 0;
    uint64_t totalBytes = 0;
    uint64_t compressedBytes = 0;
    std::string embeddingModel;
    LoadingMode loadingMode = LoadingMode::EMBEDDED;
};

struct UserCredentials {
    std::string password;
    std::string label;
    uint8_t permissions = PERMISSION_READ;
};

struct ExportConfig {
    std::string adminPassword;
    std::vector<UserCredentials> readOnlyUsers;
    LoadingMode loadingMode = LoadingMode::EMBEDDED;
    bool compress = true;
    int compressionLevel = 4;
};

struct ExportResult {
    bool success = false;
    std::string packetId;
    std::string recoveryKey;
    std::string errorMessage;
};

struct ImportResult {
    bool success = false;
    std::string packetId;
    uint8_t userPermissions = 0;
    PacketMetadata metadata;
    std::string errorMessage;
};

struct AuthResult {
    bool success = false;
    uint8_t slotId = 0;
    uint8_t permissions = 0;
    std::vector<uint8_t> decryptedDek;
    std::string errorMessage;
};

class NeuronPacketIO {
public:
    using ProgressCallback = std::function<void(float)>;

    ExportResult exportPacket(
        const std::string& outputPath,
        const PacketMetadata& metadata,
        const std::vector<uint8_t>& payload,
        const ExportConfig& config,
        ProgressCallback progress = nullptr
    );

    ImportResult openPacket(const std::string& packetPath);
    AuthResult authenticate(const std::string& password);
    std::vector<uint8_t> decryptPayload(const std::vector<uint8_t>& dek);
    void closePacket();

    bool addUser(const UserCredentials& user, const std::string& adminPassword);
    bool removeUser(uint8_t slotId, const std::string& adminPassword);
    bool changePassword(uint8_t slotId, const std::string& oldPassword, const std::string& newPassword);
    bool resetAdminPassword(const std::string& recoveryKey, const std::string& newPassword);

    const NeuronHeader& header() const { return header_; }
    const ACLManager& acl() const { return acl_; }
    bool isOpen() const { return isOpen_; }

private:
    std::vector<uint8_t> createMasterDek();
    std::vector<uint8_t> encryptDekForUser(const std::vector<uint8_t>& dek, const std::string& password, std::vector<uint8_t>& outSalt);
    std::vector<uint8_t> decryptDekForUser(const ACLEntry& entry, const std::string& password);
    std::vector<uint8_t> encryptPayload(const std::vector<uint8_t>& plaintext, const std::vector<uint8_t>& dek, bool compress);
    std::vector<uint8_t> compressData(const std::vector<uint8_t>& data);
    std::vector<uint8_t> decompressData(const std::vector<uint8_t>& data, size_t originalSize);

    NeuronHeader header_;
    ACLManager acl_;
    std::string currentPath_;
    std::vector<uint8_t> encryptedPayload_;
    std::vector<uint8_t> masterDek_;
    bool isOpen_ = false;
};

} // namespace neuron::packet