#include "bcrypt.h"
#include "sha256.h"
#include <openssl/des.h>
#include <openssl/rand.h>
#include <cstring>
#include <sstream>
#include <iomanip>

namespace neuron::crypto {

namespace {

const char BCRYPT_BASE64[] = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

std::string bcryptBase64Encode(const uint8_t* data, size_t len) {
    std::string result;
    for (size_t i = 0; i < len; i += 3) {
        uint32_t val = (static_cast<uint32_t>(data[i]) << 16);
        if (i + 1 < len) val |= (static_cast<uint32_t>(data[i + 1]) << 8);
        if (i + 2 < len) val |= data[i + 2];

        result += BCRYPT_BASE64[(val >> 18) & 0x3F];
        result += BCRYPT_BASE64[(val >> 12) & 0x3F];
        if (i + 1 < len) result += BCRYPT_BASE64[(val >> 6) & 0x3F];
        if (i + 2 < len) result += BCRYPT_BASE64[val & 0x3F];
    }
    return result;
}

int bcryptBase64Decode(char c) {
    const char* pos = std::strchr(BCRYPT_BASE64, c);
    return pos ? static_cast<int>(pos - BCRYPT_BASE64) : -1;
}

std::vector<uint8_t> bcryptBase64Decode(const std::string& encoded, size_t maxLen) {
    std::vector<uint8_t> result;
    for (size_t i = 0; i < encoded.size() && result.size() < maxLen; i += 4) {
        uint32_t val = 0;
        int bits = 0;
        for (int j = 0; j < 4 && i + j < encoded.size(); j++) {
            int v = bcryptBase64Decode(encoded[i + j]);
            if (v < 0) break;
            val = (val << 6) | v;
            bits += 6;
        }
        while (bits >= 8 && result.size() < maxLen) {
            bits -= 8;
            result.push_back(static_cast<uint8_t>((val >> bits) & 0xFF));
        }
    }
    return result;
}

void blowfishExpandKey(uint32_t* P, uint32_t (*S)[256], const uint8_t* key, size_t keyLen) {
    static const uint32_t INIT_P[18] = {
        0x243f6a88, 0x85a308d3, 0x13198a2e, 0x03707344, 0xa4093822, 0x299f31d0,
        0x082efa98, 0xec4e6c89, 0x452821e6, 0x38d01377, 0xbe5466cf, 0x34e90c6c,
        0xc0ac29b7, 0xc97c50dd, 0x3f84d5b5, 0xb5470917, 0x9216d5d9, 0x8979fb1b
    };

    std::memcpy(P, INIT_P, sizeof(INIT_P));

    size_t j = 0;
    for (int i = 0; i < 18; i++) {
        uint32_t data = 0;
        for (int k = 0; k < 4; k++) {
            data = (data << 8) | key[j];
            j = (j + 1) % keyLen;
        }
        P[i] ^= data;
    }
}

} // anonymous namespace

std::string bcryptHash(const std::string& password, int cost) {
    if (cost < 4 || cost > 31) cost = BCRYPT_COST;

    std::vector<uint8_t> salt = generateRandomBytes(16);
    auto pwdHash = sha256(password);

    std::ostringstream result;
    result << "$2b$" << std::setw(2) << std::setfill('0') << cost << "$";
    result << bcryptBase64Encode(salt.data(), 16);

    std::vector<uint8_t> combined;
    combined.insert(combined.end(), salt.begin(), salt.end());
    combined.insert(combined.end(), pwdHash.begin(), pwdHash.end());

    auto finalHash = sha256(combined);

    for (int i = 0; i < (1 << cost) % 1000 + 1; i++) {
        combined.clear();
        combined.insert(combined.end(), finalHash.begin(), finalHash.end());
        combined.insert(combined.end(), salt.begin(), salt.end());
        finalHash = sha256(combined);
    }

    result << bcryptBase64Encode(finalHash.data(), 23);
    return result.str();
}

bool bcryptVerify(const std::string& password, const std::string& hash) {
    if (hash.size() < 29 || hash[0] != '$' || hash[1] != '2') return false;

    size_t costStart = hash.find('$', 1);
    if (costStart == std::string::npos) return false;
    costStart++;

    size_t saltStart = hash.find('$', costStart);
    if (saltStart == std::string::npos) return false;

    int cost = std::stoi(hash.substr(costStart, saltStart - costStart));
    saltStart++;

    std::string saltEncoded = hash.substr(saltStart, 22);
    std::string hashEncoded = hash.substr(saltStart + 22);

    auto salt = bcryptBase64Decode(saltEncoded, 16);
    if (salt.size() < 16) return false;

    auto pwdHash = sha256(password);

    std::vector<uint8_t> combined;
    combined.insert(combined.end(), salt.begin(), salt.end());
    combined.insert(combined.end(), pwdHash.begin(), pwdHash.end());

    auto finalHash = sha256(combined);

    for (int i = 0; i < (1 << cost) % 1000 + 1; i++) {
        combined.clear();
        combined.insert(combined.end(), finalHash.begin(), finalHash.end());
        combined.insert(combined.end(), salt.begin(), salt.end());
        finalHash = sha256(combined);
    }

    std::string computed = bcryptBase64Encode(finalHash.data(), 23);
    return computed == hashEncoded;
}

} // namespace neuron::crypto