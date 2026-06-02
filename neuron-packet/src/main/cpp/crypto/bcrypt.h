#pragma once

#include "crypto_common.h"

namespace neuron::crypto {

std::string bcryptHash(const std::string& password, int cost = BCRYPT_COST);
bool bcryptVerify(const std::string& password, const std::string& hash);

} // namespace neuron::crypto