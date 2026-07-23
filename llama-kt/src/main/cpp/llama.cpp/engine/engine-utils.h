#pragma once

#include <cstring>
#include <cstdlib>

#ifdef __cplusplus
#include <string>

// Duplicate a std::string to a malloc'd C string. Caller must free().
static inline char * strdup_alloc(const std::string & s) {
    char * p = (char *)malloc(s.size() + 1);
    if (p) memcpy(p, s.c_str(), s.size() + 1);
    return p;
}

#endif
