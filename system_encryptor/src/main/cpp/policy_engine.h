#pragma once
#include <string>
#include <vector>
#include <mutex>
#include <cstdint>

namespace tn {

enum class OperationType {
    READ_VAULT = 0,
    WRITE_VAULT = 1,
    LOAD_MODEL = 2,
    EXECUTE_TOOL = 3
};

struct PolicyContext {
    std::string package_name;
    std::string caller_signature; // SHA-256 hex signature
    OperationType operation;
    std::string resource_id;
};

struct AuditRecord {
    uint64_t timestamp;
    std::string package_name;
    int operation;
    std::string resource_id;
    bool allowed;
    std::string reason;
};

class PolicyEngine {
private:
    std::mutex mtx_;
    std::vector<AuditRecord> audit_logs_;
    std::vector<uint8_t> trusted_signer_pub_; // Ed25519 public key

public:
    PolicyEngine();
    ~PolicyEngine();

    bool set_trusted_signer(const uint8_t* pub_key, size_t len);
    bool evaluate(const PolicyContext& context, const std::string& auth_token, const uint8_t* auth_token_sig, size_t sig_len);
    std::string get_audit_logs_json();
    void clear_audit_logs();
};

} // namespace tn
