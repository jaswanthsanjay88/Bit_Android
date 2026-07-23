// VT (Vision Token) cache implementation — see vt_cache.h.

#include "vt_cache.h"
#include "tn-log.h"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace fs = std::filesystem;

namespace {

constexpr uint32_t VT_MAGIC   = 0x4E4B5456;  // 'VTKN' (LE)
constexpr uint32_t VT_VERSION = 1;

#pragma pack(push, 1)
struct vt_entry_header {
    uint32_t magic;
    uint32_t version;
    int32_t  n_tokens;
    int32_t  n_embd;
    int64_t  reserved;          // for future flags (e.g. dtype, projector hash)
};
#pragma pack(pop)
static_assert(sizeof(vt_entry_header) == 24, "vt_entry_header layout");

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

struct vt_cache {
    std::string dir;
    int64_t     budget_bytes;

    struct entry {
        std::string filename;          // hex(hash) + ".vt", relative to dir
        int64_t     size_bytes;
        int64_t     last_access_ms;
        int32_t     n_tokens;
        int32_t     n_embd;
    };

    // key = hex hash string (64 chars). Trades a little memory for simpler code
    // than a custom std::array<uint8_t,32> hasher; entries are O(100) so the
    // overhead is negligible.
    std::unordered_map<std::string, entry> by_hex;
    int64_t total_bytes = 0;
    int64_t hits        = 0;
    int64_t misses      = 0;

    mutable std::mutex mutex;
};

// ── Lifecycle ───────────────────────────────────────────────────────────

vt_cache_t * vt_cache_create(const char * dir, int64_t budget_bytes) {
    if (!dir || !*dir) {
        TN_LOG_ERR("vt_cache_create: null/empty dir");
        return nullptr;
    }

    auto * c = new vt_cache();
    c->dir = dir;
    c->budget_bytes = budget_bytes > 0 ? budget_bytes : (200LL << 20);  // default 200 MB

    std::error_code ec;
    fs::create_directories(c->dir, ec);
    if (ec) {
        TN_LOG_ERR("vt_cache_create: failed to create %s: %s", dir, ec.message().c_str());
        delete c;
        return nullptr;
    }

    // Index existing .vt files. Each file is self-describing via its header.
    int32_t n_loaded = 0;
    for (auto & p : fs::directory_iterator(c->dir, ec)) {
        if (ec) break;
        if (!p.is_regular_file()) continue;
        const auto path = p.path();
        if (path.extension() != ".vt") continue;
        const auto stem = path.stem().string();
        if (stem.size() != VT_CACHE_HASH_BYTES * 2) continue;

        bool valid_hex = true;
        for (char ch : stem) {
            if (!((ch >= '0' && ch <= '9') ||
                  (ch >= 'a' && ch <= 'f') ||
                  (ch >= 'A' && ch <= 'F'))) { valid_hex = false; break; }
        }
        if (!valid_hex) continue;

        std::ifstream f(path, std::ios::binary);
        if (!f) continue;
        vt_entry_header hdr{};
        f.read((char *)&hdr, sizeof(hdr));
        if (!f || hdr.magic != VT_MAGIC || hdr.version != VT_VERSION) continue;
        if (hdr.n_tokens <= 0 || hdr.n_embd <= 0) continue;

        const int64_t expected = (int64_t)sizeof(hdr) +
                                 (int64_t)hdr.n_tokens * hdr.n_embd * (int64_t)sizeof(float);
        const int64_t actual   = (int64_t)fs::file_size(path, ec);
        if (ec || actual != expected) continue;

        vt_cache::entry e;
        e.filename       = path.filename().string();
        e.size_bytes     = actual;
        e.n_tokens       = hdr.n_tokens;
        e.n_embd         = hdr.n_embd;

        // mtime as last-access proxy. file_time -> system_clock conversion is
        // implementation-defined before C++20 file_clock helpers; this dance
        // works on libstdc++/libc++ as shipped by NDK 27.
        const auto wt    = fs::last_write_time(path, ec);
        const auto sctp  = std::chrono::time_point_cast<std::chrono::system_clock::duration>(
                                wt - decltype(wt)::clock::now() + std::chrono::system_clock::now());
        e.last_access_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                sctp.time_since_epoch()).count();

        c->by_hex[stem]  = std::move(e);
        c->total_bytes  += actual;
        n_loaded++;
    }

    TN_LOG_INF("vt_cache: opened %s — %d entries, %lld / %lld bytes",
               c->dir.c_str(), n_loaded,
               (long long)c->total_bytes, (long long)c->budget_bytes);
    return c;
}

void vt_cache_free(vt_cache_t * c) {
    if (!c) return;
    delete c;
}

// ── Configuration ───────────────────────────────────────────────────────

void vt_cache_set_budget(vt_cache_t * c, int64_t bytes) {
    if (!c) return;
    std::lock_guard<std::mutex> lk(c->mutex);
    c->budget_bytes = bytes > 0 ? bytes : (200LL << 20);

    // Trim on shrink
    while (c->total_bytes > c->budget_bytes && !c->by_hex.empty()) {
        auto oldest = c->by_hex.begin();
        for (auto it = c->by_hex.begin(); it != c->by_hex.end(); ++it) {
            if (it->second.last_access_ms < oldest->second.last_access_ms) oldest = it;
        }
        const auto path = c->dir + "/" + oldest->second.filename;
        std::error_code ec;
        fs::remove(path, ec);
        c->total_bytes -= oldest->second.size_bytes;
        c->by_hex.erase(oldest);
    }
}

int64_t vt_cache_get_budget(const vt_cache_t * c) {
    if (!c) return 0;
    std::lock_guard<std::mutex> lk(c->mutex);
    return c->budget_bytes;
}

// ── Stats ───────────────────────────────────────────────────────────────

int64_t vt_cache_total_bytes(const vt_cache_t * c) {
    if (!c) return 0;
    std::lock_guard<std::mutex> lk(c->mutex);
    return c->total_bytes;
}

int32_t vt_cache_count(const vt_cache_t * c) {
    if (!c) return 0;
    std::lock_guard<std::mutex> lk(c->mutex);
    return (int32_t)c->by_hex.size();
}

int64_t vt_cache_hits(const vt_cache_t * c) {
    if (!c) return 0;
    std::lock_guard<std::mutex> lk(c->mutex);
    return c->hits;
}

int64_t vt_cache_misses(const vt_cache_t * c) {
    if (!c) return 0;
    std::lock_guard<std::mutex> lk(c->mutex);
    return c->misses;
}

void vt_cache_reset_stats(vt_cache_t * c) {
    if (!c) return;
    std::lock_guard<std::mutex> lk(c->mutex);
    c->hits = 0;
    c->misses = 0;
}

// ── Lookup / store ──────────────────────────────────────────────────────

bool vt_cache_peek(vt_cache_t * c,
                   const uint8_t hash[VT_CACHE_HASH_BYTES],
                   int32_t * out_n_tokens, int32_t * out_n_embd) {
    if (!c) return false;
    std::lock_guard<std::mutex> lk(c->mutex);
    const auto hex = hex_encode(hash, VT_CACHE_HASH_BYTES);
    auto it = c->by_hex.find(hex);
    if (it == c->by_hex.end()) return false;
    if (out_n_tokens) *out_n_tokens = it->second.n_tokens;
    if (out_n_embd)   *out_n_embd   = it->second.n_embd;
    return true;
}

bool vt_cache_lookup(vt_cache_t * c,
                     const uint8_t hash[VT_CACHE_HASH_BYTES],
                     float * out, size_t cap_floats,
                     int32_t * out_n_tokens, int32_t * out_n_embd) {
    if (!c || !out) return false;
    std::lock_guard<std::mutex> lk(c->mutex);

    const auto hex = hex_encode(hash, VT_CACHE_HASH_BYTES);
    auto it = c->by_hex.find(hex);
    if (it == c->by_hex.end()) {
        c->misses++;
        return false;
    }

    const size_t need_floats = (size_t)it->second.n_tokens * it->second.n_embd;
    if (need_floats > cap_floats) {
        TN_LOG_WRN("vt_cache_lookup: buffer too small (%zu < %zu)", cap_floats, need_floats);
        c->misses++;
        return false;
    }

    const auto path = c->dir + "/" + it->second.filename;
    std::ifstream f(path, std::ios::binary);
    if (!f) {
        // Index points at a missing file — drop it.
        TN_LOG_WRN("vt_cache: index entry %s lost on disk, dropping", hex.c_str());
        c->total_bytes -= it->second.size_bytes;
        c->by_hex.erase(it);
        c->misses++;
        return false;
    }

    vt_entry_header hdr{};
    f.read((char *)&hdr, sizeof(hdr));
    if (!f || hdr.magic != VT_MAGIC || hdr.version != VT_VERSION) {
        TN_LOG_WRN("vt_cache: header corrupt for %s", hex.c_str());
        c->misses++;
        return false;
    }

    f.read((char *)out, need_floats * sizeof(float));
    if (!f) {
        TN_LOG_WRN("vt_cache: short read for %s", hex.c_str());
        c->misses++;
        return false;
    }

    if (out_n_tokens) *out_n_tokens = hdr.n_tokens;
    if (out_n_embd)   *out_n_embd   = hdr.n_embd;

    it->second.last_access_ms = now_ms();
    c->hits++;
    return true;
}

// Caller must hold c->mutex.
static void vt_cache_evict_locked(vt_cache_t * c, int64_t headroom) {
    while (c->total_bytes + headroom > c->budget_bytes && !c->by_hex.empty()) {
        auto oldest = c->by_hex.begin();
        for (auto it = c->by_hex.begin(); it != c->by_hex.end(); ++it) {
            if (it->second.last_access_ms < oldest->second.last_access_ms) oldest = it;
        }
        const auto path = c->dir + "/" + oldest->second.filename;
        std::error_code ec;
        fs::remove(path, ec);
        c->total_bytes -= oldest->second.size_bytes;
        c->by_hex.erase(oldest);
    }
}

bool vt_cache_store(vt_cache_t * c,
                    const uint8_t hash[VT_CACHE_HASH_BYTES],
                    const float * embd, int32_t nt, int32_t ne) {
    if (!c || !embd || nt <= 0 || ne <= 0) return false;
    std::lock_guard<std::mutex> lk(c->mutex);

    const auto   hex         = hex_encode(hash, VT_CACHE_HASH_BYTES);
    const int64_t entry_bytes = (int64_t)sizeof(vt_entry_header) +
                                (int64_t)nt * ne * (int64_t)sizeof(float);

    // Replacing an existing entry: subtract its bytes from the running total
    // before computing eviction headroom.
    auto existing = c->by_hex.find(hex);
    if (existing != c->by_hex.end()) {
        c->total_bytes -= existing->second.size_bytes;
        c->by_hex.erase(existing);
    }

    if (entry_bytes > c->budget_bytes) {
        TN_LOG_WRN("vt_cache_store: entry %lld bytes > budget %lld; refusing",
                   (long long)entry_bytes, (long long)c->budget_bytes);
        return false;
    }

    vt_cache_evict_locked(c, entry_bytes);

    const auto path     = c->dir + "/" + hex + ".vt";
    const auto path_tmp = path + ".tmp";

    {
        std::ofstream f(path_tmp, std::ios::binary | std::ios::trunc);
        if (!f) {
            TN_LOG_ERR("vt_cache_store: cannot open %s", path_tmp.c_str());
            return false;
        }
        vt_entry_header hdr{ VT_MAGIC, VT_VERSION, nt, ne, 0 };
        f.write((const char *)&hdr,  sizeof(hdr));
        f.write((const char *)embd, (size_t)nt * ne * sizeof(float));
        if (!f) {
            std::error_code ec;
            fs::remove(path_tmp, ec);
            TN_LOG_ERR("vt_cache_store: write failed for %s", path_tmp.c_str());
            return false;
        }
    }

    std::error_code ec;
    fs::rename(path_tmp, path, ec);
    if (ec) {
        // Some Android filesystems reject rename across mount points or for
        // existing destinations — fall back to remove+rename.
        fs::remove(path, ec);
        fs::rename(path_tmp, path, ec);
        if (ec) {
            fs::remove(path_tmp, ec);
            TN_LOG_ERR("vt_cache_store: rename failed for %s: %s",
                       path.c_str(), ec.message().c_str());
            return false;
        }
    }

    vt_cache::entry e;
    e.filename       = hex + ".vt";
    e.size_bytes     = entry_bytes;
    e.last_access_ms = now_ms();
    e.n_tokens       = nt;
    e.n_embd         = ne;
    c->by_hex[hex]   = std::move(e);
    c->total_bytes  += entry_bytes;

    TN_LOG_INF("vt_cache_store: %s (%d tokens × %d embd = %lld bytes), total %lld / %lld",
               hex.c_str(), nt, ne, (long long)entry_bytes,
               (long long)c->total_bytes, (long long)c->budget_bytes);
    return true;
}

// ── Management ──────────────────────────────────────────────────────────

void vt_cache_clear(vt_cache_t * c) {
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

bool vt_cache_remove(vt_cache_t * c, const uint8_t hash[VT_CACHE_HASH_BYTES]) {
    if (!c) return false;
    std::lock_guard<std::mutex> lk(c->mutex);
    const auto hex = hex_encode(hash, VT_CACHE_HASH_BYTES);
    auto it = c->by_hex.find(hex);
    if (it == c->by_hex.end()) return false;
    std::error_code ec;
    fs::remove(c->dir + "/" + it->second.filename, ec);
    c->total_bytes -= it->second.size_bytes;
    c->by_hex.erase(it);
    return true;
}

vt_cache_entry_info * vt_cache_list(vt_cache_t * c, int32_t * out_count) {
    if (!c || !out_count) return nullptr;
    std::lock_guard<std::mutex> lk(c->mutex);
    *out_count = (int32_t)c->by_hex.size();
    if (*out_count == 0) return nullptr;

    auto * arr = (vt_cache_entry_info *)std::malloc(sizeof(vt_cache_entry_info) * (size_t)*out_count);
    if (!arr) { *out_count = 0; return nullptr; }

    int32_t i = 0;
    for (auto & kv : c->by_hex) {
        auto & info = arr[i++];
        // Decode hex back to bytes
        hex_decode_into(kv.first, info.hash, VT_CACHE_HASH_BYTES);
        info.n_tokens       = kv.second.n_tokens;
        info.n_embd         = kv.second.n_embd;
        info.size_bytes     = kv.second.size_bytes;
        info.last_access_ms = kv.second.last_access_ms;
    }
    return arr;
}

void vt_cache_list_free(vt_cache_entry_info * entries) {
    std::free(entries);
}
