#include "pbkdf2.h"
#include <openssl/evp.h>
#include <stdexcept>

namespace neuron::crypto {

std::vector<uint8_t> pbkdf2(
    const std::string& password,
    const std::vector<uint8_t>& salt,
    int iterations,
    size_t keyLength
) {
    std::vector<uint8_t> key(keyLength);

    int result = PKCS5_PBKDF2_HMAC(
        password.c_str(),
        static_cast<int>(password.size()),
        salt.data(),
        static_cast<int>(salt.size()),
        iterations,
        EVP_sha256(),
        static_cast<int>(keyLength),
        key.data()
    );

    if (result != 1) {
        throw std::runtime_error("PBKDF2 key derivation failed");
    }

    return key;
}

DerivedKey deriveKey(const std::string& password, const std::vector<uint8_t>* existingSalt) {
    DerivedKey result;
    result.salt = existingSalt ? *existingSalt : generateRandomBytes(SALT_SIZE);
    result.key = pbkdf2(password, result.salt);
    return result;
}

} // namespace neuron::crypto