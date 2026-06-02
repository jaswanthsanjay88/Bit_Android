#include "aes_gcm.h"
#include <openssl/evp.h>
#include <stdexcept>

namespace neuron::crypto {

EncryptedData aesGcmEncrypt(
    const std::vector<uint8_t>& plaintext,
    const std::vector<uint8_t>& key,
    const std::vector<uint8_t>* aad
) {
    if (key.size() != AES_KEY_SIZE) {
        throw std::invalid_argument("Invalid key size");
    }

    EncryptedData result;
    result.iv = generateRandomBytes(AES_IV_SIZE);
    result.ciphertext.resize(plaintext.size());
    result.tag.resize(AES_TAG_SIZE);

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) throw std::runtime_error("Failed to create cipher context");

    int len = 0;
    bool success = false;

    do {
        if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1) break;
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, AES_IV_SIZE, nullptr) != 1) break;
        if (EVP_EncryptInit_ex(ctx, nullptr, nullptr, key.data(), result.iv.data()) != 1) break;

        if (aad && !aad->empty()) {
            if (EVP_EncryptUpdate(ctx, nullptr, &len, aad->data(), static_cast<int>(aad->size())) != 1) break;
        }

        if (EVP_EncryptUpdate(ctx, result.ciphertext.data(), &len, plaintext.data(), static_cast<int>(plaintext.size())) != 1) break;

        int ciphertextLen = len;
        if (EVP_EncryptFinal_ex(ctx, result.ciphertext.data() + len, &len) != 1) break;
        ciphertextLen += len;
        result.ciphertext.resize(ciphertextLen);

        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, AES_TAG_SIZE, result.tag.data()) != 1) break;

        success = true;
    } while (false);

    EVP_CIPHER_CTX_free(ctx);

    if (!success) throw std::runtime_error("Encryption failed");
    return result;
}

std::vector<uint8_t> aesGcmDecrypt(
    const EncryptedData& encrypted,
    const std::vector<uint8_t>& key,
    const std::vector<uint8_t>* aad
) {
    if (key.size() != AES_KEY_SIZE) {
        throw std::invalid_argument("Invalid key size");
    }

    std::vector<uint8_t> plaintext(encrypted.ciphertext.size());

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) throw std::runtime_error("Failed to create cipher context");

    int len = 0;
    bool success = false;

    do {
        if (EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1) break;
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, AES_IV_SIZE, nullptr) != 1) break;
        if (EVP_DecryptInit_ex(ctx, nullptr, nullptr, key.data(), encrypted.iv.data()) != 1) break;

        if (aad && !aad->empty()) {
            if (EVP_DecryptUpdate(ctx, nullptr, &len, aad->data(), static_cast<int>(aad->size())) != 1) break;
        }

        if (EVP_DecryptUpdate(ctx, plaintext.data(), &len, encrypted.ciphertext.data(), static_cast<int>(encrypted.ciphertext.size())) != 1) break;

        int plaintextLen = len;

        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, AES_TAG_SIZE, const_cast<uint8_t*>(encrypted.tag.data())) != 1) break;

        if (EVP_DecryptFinal_ex(ctx, plaintext.data() + len, &len) != 1) break;
        plaintextLen += len;
        plaintext.resize(plaintextLen);

        success = true;
    } while (false);

    EVP_CIPHER_CTX_free(ctx);

    if (!success) throw std::runtime_error("Decryption failed - authentication error");
    return plaintext;
}

std::vector<uint8_t> aesGcmDecrypt(
    const std::vector<uint8_t>& data,
    const std::vector<uint8_t>& key,
    const std::vector<uint8_t>* aad
) {
    return aesGcmDecrypt(EncryptedData::fromBytes(data), key, aad);
}

} // namespace neuron::crypto