#pragma once
#include <vector>
#include <cstdint>
#include <cstddef>

namespace tn {

class HxsVaultEngine {
public:
    HxsVaultEngine();
    ~HxsVaultEngine();

    std::vector<uint8_t> encrypt(
        const uint8_t* plaintext, size_t plaintext_len,
        const uint8_t* master_key, size_t master_key_len,
        const uint8_t* signer_pub, size_t signer_pub_len,
        const uint8_t* signer_priv, size_t signer_priv_len
    );

    std::vector<uint8_t> decrypt(
        const uint8_t* hxs_block, size_t block_len,
        const uint8_t* master_key, size_t master_key_len
    );
};

} // namespace tn
