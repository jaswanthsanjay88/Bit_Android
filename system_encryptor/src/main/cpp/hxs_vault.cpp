#include "hxs_vault.h"
#include "crypto_engine.h"
#include <cstring>

namespace tn {

static CryptoEngine s_crypto;

constexpr size_t HXS_HEADER_SIZE = 116; // 4 + 32 + 16 + 64
constexpr uint8_t HXS_MAGIC[4] = {'H', 'X', 'S', 0x01};

HxsVaultEngine::HxsVaultEngine() {}
HxsVaultEngine::~HxsVaultEngine() {}

std::vector<uint8_t> HxsVaultEngine::encrypt(
    const uint8_t* plaintext, size_t plaintext_len,
    const uint8_t* master_key, size_t master_key_len,
    const uint8_t* signer_pub, size_t signer_pub_len,
    const uint8_t* signer_priv, size_t signer_priv_len
) {
    if (!plaintext || plaintext_len == 0) return {};
    if (!master_key || master_key_len != AES_KEY_SIZE) return {};
    if (!signer_pub || signer_pub_len != ED25519_PUBLIC_KEY_SIZE) return {};
    if (!signer_priv || signer_priv_len != ED25519_PRIVATE_KEY_SIZE) return {};

    // 1. Generate random 16-byte salt
    uint8_t salt[16];
    if (!s_crypto.random_bytes(salt, 16)) return {};

    // 2. Derive signer-bound AES-256 key via HKDF (info = signer_pub)
    uint8_t derived_key[AES_KEY_SIZE];
    bool hkdf_ok = s_crypto.hkdf_sha256(
        master_key, master_key_len,
        salt, 16,
        signer_pub, signer_pub_len,
        derived_key, AES_KEY_SIZE
    );
    if (!hkdf_ok) return {};

    // 3. Encrypt using AES-256-GCM
    auto encrypt_result = s_crypto.encrypt_aes_gcm(
        plaintext, plaintext_len,
        derived_key, AES_KEY_SIZE,
        nullptr, 0
    );
    secure_zero(derived_key, AES_KEY_SIZE);

    if (!encrypt_result.success) return {};

    // 4. Generate Ed25519 signature over plaintext
    uint8_t signature[ED25519_SIGNATURE_SIZE];
    bool sign_ok = s_crypto.sign_ed25519(
        plaintext, plaintext_len,
        signer_priv,
        signature
    );
    if (!sign_ok) return {};

    // 5. Pack result
    std::vector<uint8_t> packed(HXS_HEADER_SIZE + encrypt_result.sealed_data.size());
    uint8_t* dest = packed.data();

    std::memcpy(dest, HXS_MAGIC, 4);
    dest += 4;
    std::memcpy(dest, signer_pub, ED25519_PUBLIC_KEY_SIZE);
    dest += ED25519_PUBLIC_KEY_SIZE;
    std::memcpy(dest, salt, 16);
    dest += 16;
    std::memcpy(dest, signature, ED25519_SIGNATURE_SIZE);
    dest += ED25519_SIGNATURE_SIZE;

    std::memcpy(dest, encrypt_result.sealed_data.data(), encrypt_result.sealed_data.size());

    return packed;
}

std::vector<uint8_t> HxsVaultEngine::decrypt(
    const uint8_t* hxs_block, size_t block_len,
    const uint8_t* master_key, size_t master_key_len
) {
    if (!hxs_block || block_len < HXS_HEADER_SIZE) return {};
    if (!master_key || master_key_len != AES_KEY_SIZE) return {};

    // 1. Unpack header
    const uint8_t* src = hxs_block;
    if (std::memcmp(src, HXS_MAGIC, 4) != 0) return {};
    src += 4;

    const uint8_t* signer_pub = src;
    src += ED25519_PUBLIC_KEY_SIZE;

    const uint8_t* salt = src;
    src += 16;

    const uint8_t* signature = src;
    src += ED25519_SIGNATURE_SIZE;

    const uint8_t* sealed_data = src;
    size_t sealed_len = block_len - HXS_HEADER_SIZE;

    // 2. Derive signer-bound AES-256 key via HKDF (info = signer_pub)
    uint8_t derived_key[AES_KEY_SIZE];
    bool hkdf_ok = s_crypto.hkdf_sha256(
        master_key, master_key_len,
        salt, 16,
        signer_pub, ED25519_PUBLIC_KEY_SIZE,
        derived_key, AES_KEY_SIZE
    );
    if (!hkdf_ok) return {};

    // 3. Decrypt using AES-256-GCM
    auto decrypt_result = s_crypto.decrypt_aes_gcm(
        sealed_data, sealed_len,
        derived_key, AES_KEY_SIZE,
        nullptr, 0
    );
    secure_zero(derived_key, AES_KEY_SIZE);

    if (!decrypt_result.success) return {};

    // 4. Verify Ed25519 signature over plaintext
    bool verify_ok = s_crypto.verify_ed25519(
        decrypt_result.plaintext.data(),
        decrypt_result.plaintext.size(),
        signature,
        signer_pub
    );

    if (!verify_ok) {
        decrypt_result.plaintext.wipe();
        return {};
    }

    // 5. Return decrypted plaintext
    std::vector<uint8_t> plaintext(
        decrypt_result.plaintext.data(),
        decrypt_result.plaintext.data() + decrypt_result.plaintext.size()
    );
    decrypt_result.plaintext.wipe();

    return plaintext;
}

} // namespace tn
