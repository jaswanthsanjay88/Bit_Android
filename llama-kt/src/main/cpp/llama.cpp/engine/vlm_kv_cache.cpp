// VLM KV cache implementation — see vlm_kv_cache.h.

#include "vlm_kv_cache.h"
#include "tn-log.h"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <unordered_map>

namespace fs = std::filesystem;

namespace {

constexpr uint32_t VKV_MAGIC   = 0x564B564C;  // 'VKVL' (LE) — VLM-KV-LLM
constexpr uint32_t VKV_VERSION = 1;

#pragma pack(push, 1)
struct vlm_kv_entry_header {
    uint32_t magic;
    uint32_t version;
    int32_t  n_tokens;
    int32_t  reserved_a;
    int64_t  blob_size;
    int64_t  reserved_b;
};
#pragma pack(pop)
static_assert(sizeof(vlm_kv_entry_header) == 32, "vlm_kv_entry_header layout");

inline std::string hex_encode(const uint8_t * h, size_t n) {
    static const char d[] = "0123456789abcdef";
    std::string s; s.resize(n * 2);
    for (size_t i = 0; i < n; i++) {
        s[i*2  ] = d[h[i] >> 4];
        s[i*2+1] = d[h[i] & 0xF];
    }
    return s;
}

inline bool hex_decode_into(const std::string & hex, uint8_t * out, size_t out_size) {
    if (hex.size() != out_size * 2) return false;
    auto digit = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + c - 'a';
        if (c >= 'A' && c <= 'F') return 10 + c - 'A';
        return -1;
    };
    for (size_t i = 0; i < out_size; i++) {
        int hi = digit(hex[i*2]);
        int lo = digit(hex[i*2+1]);
        if (hi < 0 || lo < 0) return false;
        out[i] = (uint8_t)((hi << 4) | lo);
    }
    return true;
}

inline int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

} // namespace

struct vlm_kv_cache {
    std::string dir;
    int64_t     budget_bytes;

    struct entry {
        std::string filename;
        int64_t     size_bytes;       // total file size (header + blob)
        int64_t     last_access_ms;
        int32_t     n_tokens;
        int64_t     blob_size;
    };

    std::unordered_map<std::string, entry> by_hex;
    int64_t total_bytes = 0;
    int64_t hits        = 0;
    int64_t misses      = 0;

    mutable std::mutex mutex;
};

// ── Lifecycle ───────────────────────────────────────────────────────────

vlm_kv_cache_t * vlm_kv_cache_create(const char * dir, int64_t budget_bytes) {
    if (!dir || !*dir) {
        TN_LOG_ERR("vlm_kv_cache_create: null/empty dir");
        return nullptr;
    }

    auto * c = new vlm_kv_cache();
    c->dir = dir;
    c->budget_bytes = budget_bytes > 0 ? budget_bytes : (300LL << 20);  // default 300 MB

    std::error_code ec;
    fs::create_directories(c->dir, ec);
    if (ec) {
        TN_LOG_ERR("vlm_kv_cache_create: failed to create %s: %s", dir, ec.message().c_str());
        delete c;
        return nullptr;
    }

    int32_t n_loaded = 0;
    for (auto & p : fs::directory_iterator(c->dir, ec)) {
        if (ec) break;
        if (!p.is_regular_file()) continue;
        const auto path = p.path();
        if (path.extension() != ".vkv") continue;
        const auto stem = path.stem().string();
        if (stem.size() != VLM_KV_CACHE_HASH_BYTES * 2) continue;

        bool valid_hex = true;
        for (char ch : stem) {
            if (!((ch >= '0' && ch <= '9') ||
                  (ch >= 'a' && ch <= 'f') ||
                  (ch >= 'A' && ch <= 'F'))) { valid_hex = false; break; }
        }
        if (!valid_hex) continue;

        std::ifstream f(path, std::ios::binary);
        if (!f) continue;
        vlm_kv_entry_header hdr{};
        f.read((char *)&hdr, sizeof(hdr));
        if (!f || hdr.magic != VKV_MAGIC || hdr.version != VKV_VERSION) continue;
        if (hdr.n_tokens <= 0 || hdr.blob_size <= 0) continue;

        const int64_t expected = (int64_t)sizeof(hdr) + hdr.blob_size;
        const int64_t actual   = (int64_t)fs::file_size(path, ec);
        if (ec || actual != expected) continue;

        vlm_kv_cache::entry e;
        e.filename       = path.filename().string();
        e.size_bytes     = actual;
        e.n_tokens       = hdr.n_tokens;
        e.blob_size      = hdr.blob_size;

        const auto wt    = fs::last_write_time(path, ec);
        const auto sctp  = std::chrono::time_point_cast<std::chrono::system_clock::duration>(
                                wt - decltype(wt)::clock::now() + std::chrono::system_clock::now());
        e.last_access_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                sctp.time_since_epoch()).count();

        c->by_hex[stem]  = std::move(e);
        c->total_bytes  += actual;
        n_loaded++;
    }

    TN_LOG_INF("vlm_kv_cache: opened %s — %d entries, %lld / %lld bytes",
               c->dir.c_str(), n_loaded,
               (long long)c->total_bytes, (long long)c->budget_bytes);
    return c;
}

void vlm_kv_cache_free(vlm_kv_cache_t * c) {
    if (!c) return;
    delete c;
}

// ── Configuration ───────────────────────────────────────────────────────

static void evict_locked(vlm_kv_cache_t * c, int64_t headroom) {
    while (c->total_bytes + headroom > c->budget_bytes && !c->by_hex.empty()) {
        auto oldest = c->by_hex.begin();
        for (auto it = c->by_hex.begin(); it != c->by_hex.end(); ++it) {
            if (it->second.last_access_ms < oldest->second.last_access_ms) oldest = it;
        }
        std::error_code ec;
        fs::remove(c->dir + "/" + oldest->second.filename, ec);
        c->total_bytes -= oldest->second.size_bytes;
        c->by_hex.erase(oldest);
    }
}

void vlm_kv_cache_set_budget(vlm_kv_cache_t * c, int64_t bytes) {
    if (!c) return;
    std::lock_guard<std::mutex> lk(c->mutex);
    c->budget_bytes = bytes > 0 ? bytes : (300LL << 20);
    evict_locked(c, 0);
}

int64_t vlm_kv_cache_get_budget(const vlm_kv_cache_t * c) {
    if (!c) return 0;
    std::lock_guard<std::mutex> lk(c->mutex);
    return c->budget_bytes;
}

// ── Stats ───────────────────────────────────────────────────────────────

int64_t vlm_kv_cache_total_bytes(const vlm_kv_cache_t * c) {
    if (!c) return 0;
    std::lock_guard<std::mutex> lk(c->mutex);
    return c->total_bytes;
}

int32_t vlm_kv_cache_count(const vlm_kv_cache_t * c) {
    if (!c) return 0;
    std::lock_guard<std::mutex> lk(c->mutex);
    return (int32_t)c->by_hex.size();
}

int64_t vlm_kv_cache_hits(const vlm_kv_cache_t * c) {
    if (!c) return 0;
    std::lock_guard<std::mutex> lk(c->mutex);
    return c->hits;
}

int64_t vlm_kv_cache_misses(const vlm_kv_cache_t * c) {
    if (!c) return 0;
    std::lock_guard<std::mutex> lk(c->mutex);
    return c->misses;
}

void vlm_kv_cache_reset_stats(vlm_kv_cache_t * c) {
    if (!c) return;
    std::lock_guard<std::mutex> lk(c->mutex);
    c->hits   = 0;
    c->misses = 0;
}

// ── Lookup / store ──────────────────────────────────────────────────────

bool vlm_kv_cache_peek(vlm_kv_cache_t * c,
                       const uint8_t hash[VLM_KV_CACHE_HASH_BYTES],
                       int32_t * out_n_tokens,
                       size_t  * out_blob_size) {
    if (!c) return false;
    std::lock_guard<std::mutex> lk(c->mutex);
    const auto hex = hex_encode(hash, VLM_KV_CACHE_HASH_BYTES);
    auto it = c->by_hex.find(hex);
    if (it == c->by_hex.end()) return false;
    if (out_n_tokens)  *out_n_tokens  = it->second.n_tokens;
    if (out_blob_size) *out_blob_size = (size_t)it->second.blob_size;
    return true;
}

bool vlm_kv_cache_lookup(vlm_kv_cache_t * c,
                         const uint8_t hash[VLM_KV_CACHE_HASH_BYTES],
                         uint8_t * out_blob, size_t cap, size_t * out_size,
                         int32_t * out_n_tokens) {
    if (!c || !out_blob) return false;
    std::lock_guard<std::mutex> lk(c->mutex);

    const auto hex = hex_encode(hash, VLM_KV_CACHE_HASH_BYTES);
    auto it = c->by_hex.find(hex);
    if (it == c->by_hex.end()) {
        c->misses++;
        return false;
    }

    if ((size_t)it->second.blob_size > cap) {
        TN_LOG_WRN("vlm_kv_cache_lookup: buffer too small (%zu < %lld)",
                   cap, (long long)it->second.blob_size);
        c->misses++;
        return false;
    }

    const auto path = c->dir + "/" + it->second.filename;
    std::ifstream f(path, std::ios::binary);
    if (!f) {
        TN_LOG_WRN("vlm_kv_cache: index entry %s lost on disk, dropping", hex.c_str());
        c->total_bytes -= it->second.size_bytes;
        c->by_hex.erase(it);
        c->misses++;
        return false;
    }

    vlm_kv_entry_header hdr{};
    f.read((char *)&hdr, sizeof(hdr));
    if (!f || hdr.magic != VKV_MAGIC || hdr.version != VKV_VERSION) {
        TN_LOG_WRN("vlm_kv_cache: header corrupt for %s", hex.c_str());
        c->misses++;
        return false;
    }

    f.read((char *)out_blob, hdr.blob_size);
    if (!f) {
        TN_LOG_WRN("vlm_kv_cache: short read for %s", hex.c_str());
        c->misses++;
        return false;
    }

    if (out_size)     *out_size     = (size_t)hdr.blob_size;
    if (out_n_tokens) *out_n_tokens = hdr.n_tokens;

    it->second.last_access_ms = now_ms();
    c->hits++;
    return true;
}

bool vlm_kv_cache_store(vlm_kv_cache_t * c,
                        const uint8_t hash[VLM_KV_CACHE_HASH_BYTES],
                        const uint8_t * blob, size_t blob_size, int32_t n_tokens) {
    if (!c || !blob || blob_size == 0 || n_tokens <= 0) return false;
    std::lock_guard<std::mutex> lk(c->mutex);

    const auto    hex         = hex_encode(hash, VLM_KV_CACHE_HASH_BYTES);
    const int64_t entry_bytes = (int64_t)sizeof(vlm_kv_entry_header) + (int64_t)blob_size;

    auto existing = c->by_hex.find(hex);
    if (existing != c->by_hex.end()) {
        c->total_bytes -= existing->second.size_bytes;
        c->by_hex.erase(existing);
    }

    if (entry_bytes > c->budget_bytes) {
        TN_LOG_WRN("vlm_kv_cache_store: entry %lld bytes > budget %lld; refusing",
                   (long long)entry_bytes, (long long)c->budget_bytes);
        return false;
    }

    evict_locked(c, entry_bytes);

    const auto path     = c->dir + "/" + hex + ".vkv";
    const auto path_tmp = path + ".tmp";

    {
        std::ofstream f(path_tmp, std::ios::binary | std::ios::trunc);
        if (!f) {
            TN_LOG_ERR("vlm_kv_cache_store: cannot open %s", path_tmp.c_str());
            return false;
        }
        vlm_kv_entry_header hdr{ VKV_MAGIC, VKV_VERSION, n_tokens, 0, (int64_t)blob_size, 0 };
        f.write((const char *)&hdr,  sizeof(hdr));
        f.write((const char *)blob, blob_size);
        if (!f) {
            std::error_code ec;
            fs::remove(path_tmp, ec);
            TN_LOG_ERR("vlm_kv_cache_store: write failed for %s", path_tmp.c_str());
            return false;
        }
    }

    std::error_code ec;
    fs::rename(path_tmp, path, ec);
    if (ec) {
        fs::remove(path, ec);
        fs::rename(path_tmp, path, ec);
        if (ec) {
            fs::remove(path_tmp, ec);
            TN_LOG_ERR("vlm_kv_cache_store: rename failed for %s: %s",
                       path.c_str(), ec.message().c_str());
            return false;
        }
    }

    vlm_kv_cache::entry e;
    e.filename       = hex + ".vkv";
    e.size_bytes     = entry_bytes;
    e.last_access_ms = now_ms();
    e.n_tokens       = n_tokens;
    e.blob_size      = (int64_t)blob_size;
    c->by_hex[hex]   = std::move(e);
    c->total_bytes  += entry_bytes;

    TN_LOG_INF("vlm_kv_cache_store: %s (%d tokens, blob %lld bytes), total %lld / %lld",
               hex.c_str(), n_tokens, (long long)blob_size,
               (long long)c->total_bytes, (long long)c->budget_bytes);
    return true;
}

// ── Management ──────────────────────────────────────────────────────────

void vlm_kv_cache_clear(vlm_kv_cache_t * c) {
    if (!c) return;
    std::lock_guard<std::mutex> lk(c->mutex);
    std::error_code ec;
    for (auto & kv : c->by_hex) {
        fs::remove(c->dir + "/" + kv.second.filename, ec);
    }
    c->by_hex.clear();
    c->total_bytes = 0;
    c->hits = 0;
    c->misses = 0;
}

bool vlm_kv_cache_remove(vlm_kv_cache_t * c, const uint8_t hash[VLM_KV_CACHE_HASH_BYTES]) {
    if (!c) return false;
    std::lock_guard<std::mutex> lk(c->mutex);
    const auto hex = hex_encode(hash, VLM_KV_CACHE_HASH_BYTES);
    auto it = c->by_hex.find(hex);
    if (it == c->by_hex.end()) return false;
    std::error_code ec;
    fs::remove(c->dir + "/" + it->second.filename, ec);
    c->total_bytes -= it->second.size_bytes;
    c->by_hex.erase(it);
    return true;
}

vlm_kv_cache_entry_info * vlm_kv_cache_list(vlm_kv_cache_t * c, int32_t * out_count) {
    if (!c || !out_count) return nullptr;
    std::lock_guard<std::mutex> lk(c->mutex);
    *out_count = (int32_t)c->by_hex.size();
    if (*out_count == 0) return nullptr;

    auto * arr = (vlm_kv_cache_entry_info *)std::malloc(sizeof(vlm_kv_cache_entry_info) * (size_t)*out_count);
    if (!arr) { *out_count = 0; return nullptr; }

    int32_t i = 0;
    for (auto & kv : c->by_hex) {
        auto & info = arr[i++];
        hex_decode_into(kv.first, info.hash, VLM_KV_CACHE_HASH_BYTES);
        info.n_tokens       = kv.second.n_tokens;
        info.size_bytes     = kv.second.size_bytes;
        info.last_access_ms = kv.second.last_access_ms;
    }
    return arr;
}

void vlm_kv_cache_list_free(vlm_kv_cache_entry_info * entries) {
    std::free(entries);
}
