#include "policy_engine.h"
#include "crypto_engine.h"
#include <sstream>
#include <chrono>
#include <algorithm>

namespace tn {

static CryptoEngine s_crypto;

PolicyEngine::PolicyEngine() {}
PolicyEngine::~PolicyEngine() {}

bool PolicyEngine::set_trusted_signer(const uint8_t* pub_key, size_t len) {
    std::lock_guard<std::mutex> lock(mtx_);
    if (len != ED25519_PUBLIC_KEY_SIZE) return false;
    trusted_signer_pub_.assign(pub_key, pub_key + len);
    return true;
}

static std::string get_token_value(const std::string& token, const std::string& key) {
    size_t pos = token.find(key + ":");
    if (pos == std::string::npos) return "";
    pos += key.length() + 1;
    size_t end_pos = token.find('|', pos);
    if (end_pos == std::string::npos) {
        return token.substr(pos);
    }
    return token.substr(pos, end_pos - pos);
}

bool PolicyEngine::evaluate(
    const PolicyContext& context,
    const std::string& auth_token,
    const uint8_t* auth_token_sig,
    size_t sig_len
) {
    std::lock_guard<std::mutex> lock(mtx_);
    uint64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();

    AuditRecord record;
    record.timestamp = now;
    record.package_name = context.package_name;
    record.operation = static_cast<int>(context.operation);
    record.resource_id = context.resource_id;
    record.allowed = false;

    // 1. Check if trusted signer public key is set
    if (trusted_signer_pub_.size() != ED25519_PUBLIC_KEY_SIZE) {
        record.reason = "Trusted signer public key not configured";
        audit_logs_.push_back(record);
        return false;
    }

    // 2. Verify signature over token
    if (sig_len != ED25519_SIGNATURE_SIZE || !auth_token_sig) {
        record.reason = "Invalid signature length";
        audit_logs_.push_back(record);
        return false;
    }

    bool sig_ok = s_crypto.verify_ed25519(
        reinterpret_cast<const uint8_t*>(auth_token.data()),
        auth_token.length(),
        auth_token_sig,
        trusted_signer_pub_.data()
    );

    if (!sig_ok) {
        record.reason = "Token signature verification failed";
        audit_logs_.push_back(record);
        return false;
    }

    // 3. Parse token fields
    std::string token_pkg = get_token_value(auth_token, "package");
    std::string token_op_str = get_token_value(auth_token, "operation");
    std::string token_res = get_token_value(auth_token, "resourceId");
    std::string token_exp_str = get_token_value(auth_token, "expiry");

    if (token_pkg.empty() || token_op_str.empty() || token_exp_str.empty()) {
        record.reason = "Malformed token format";
        audit_logs_.push_back(record);
        return false;
    }

    // 4. Validate package name matches
    if (token_pkg != "*" && token_pkg != context.package_name) {
        record.reason = "Package mismatch: token=" + token_pkg + ", context=" + context.package_name;
        audit_logs_.push_back(record);
        return false;
    }

    // 5. Validate operation type matches
    int token_op = std::stoi(token_op_str);
    if (token_op != -1 && token_op != static_cast<int>(context.operation)) {
        record.reason = "Operation mismatch: token=" + token_op_str + ", context=" + std::to_string(static_cast<int>(context.operation));
        audit_logs_.push_back(record);
        return false;
    }

    // 6. Validate target resource matches
    if (!token_res.empty() && token_res != "*" && token_res != context.resource_id) {
        record.reason = "Resource mismatch: token=" + token_res + ", context=" + context.resource_id;
        audit_logs_.push_back(record);
        return false;
    }

    // 7. Validate expiration
    uint64_t token_exp = std::stoull(token_exp_str);
    if (now > token_exp) {
        record.reason = "Token expired: current=" + std::to_string(now) + ", expiry=" + token_exp_str;
        audit_logs_.push_back(record);
        return false;
    }

    // All checks passed!
    record.allowed = true;
    record.reason = "Authorized successfully";
    audit_logs_.push_back(record);
    return true;
}

std::string PolicyEngine::get_audit_logs_json() {
    std::lock_guard<std::mutex> lock(mtx_);
    std::stringstream ss;
    ss << "[";
    for (size_t i = 0; i < audit_logs_.size(); ++i) {
        const auto& rec = audit_logs_[i];
        ss << "{";
        ss << "\"timestamp\":" << rec.timestamp << ",";
        ss << "\"packageName\":\"" << rec.package_name << "\",";
        ss << "\"operation\":" << rec.operation << ",";
        ss << "\"resourceId\":\"" << rec.resource_id << "\",";
        ss << "\"allowed\":" << (rec.allowed ? "true" : "false") << ",";
        ss << "\"reason\":\"" << rec.reason << "\"";
        ss << "}";
        if (i + 1 < audit_logs_.size()) ss << ",";
    }
    ss << "]";
    return ss.str();
}

void PolicyEngine::clear_audit_logs() {
    std::lock_guard<std::mutex> lock(mtx_);
    audit_logs_.clear();
}

} // namespace tn
