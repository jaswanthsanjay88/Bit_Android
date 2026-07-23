// VT (Vision Token) cache — content-addressed store for ViT-encoded image
// embeddings. Lets us skip the ~9-10s vision encoder pass on repeat queries
// over the same image. Analogous to llama.cpp's KV cache, but for the output
// of mtmd_encode_chunk.
//
// Storage layout:
//   {dir}/{hex(hash)}.vt    — one file per cached entry
//
// Each .vt file is:
//   vt_entry_header { magic, version, n_tokens, n_embd, reserved }
//   float[n_tokens * n_embd]
//
// Hash is opaque to this layer — caller derives it (e.g. SHA256 of decoded
// pixels + projector identity + image_max_tokens). See clients of this API
// for the canonical key derivation.
//
// Eviction: LRU by last_access_ms. Triggered on store when total > budget.
//
// Thread safety: every public function takes the cache's internal mutex.
// Safe to call from any thread.

#pragma once

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VT_CACHE_HASH_BYTES 32   // 256-bit hash (caller's choice — SHA256 recommended)

typedef struct vt_cache vt_cache_t;

typedef struct {
    uint8_t  hash[VT_CACHE_HASH_BYTES];
    int32_t  n_tokens;
    int32_t  n_embd;
    int64_t  size_bytes;
    int64_t  last_access_ms;     // unix epoch milliseconds
} vt_cache_entry_info;

// ── Lifecycle ───────────────────────────────────────────────────────────

// Creates the cache directory if needed and indexes any pre-existing .vt
// files left there from a previous session. Returns NULL if dir is unwritable.
vt_cache_t * vt_cache_create(const char * dir, int64_t budget_bytes);
void         vt_cache_free  (vt_cache_t * c);

// ── Configuration ───────────────────────────────────────────────────────

void    vt_cache_set_budget (vt_cache_t * c, int64_t bytes);
int64_t vt_cache_get_budget (const vt_cache_t * c);

// ── Stats ───────────────────────────────────────────────────────────────

int64_t vt_cache_total_bytes(const vt_cache_t * c);
int32_t vt_cache_count      (const vt_cache_t * c);
int64_t vt_cache_hits       (const vt_cache_t * c);
int64_t vt_cache_misses     (const vt_cache_t * c);
void    vt_cache_reset_stats(vt_cache_t * c);

// ── Lookup / store ──────────────────────────────────────────────────────

// Cheap header-only check. Does NOT touch LRU. Use to query whether an entry
// exists and to size the caller's read buffer.
bool vt_cache_peek(vt_cache_t * c,
                   const uint8_t hash[VT_CACHE_HASH_BYTES],
                   int32_t * out_n_tokens,
                   int32_t * out_n_embd);

// Read embeddings into caller-provided buffer. Touches LRU on hit.
//
// out_capacity_floats must be at least n_tokens * n_embd. Use vt_cache_peek
// first if you don't know the size.
//
// Returns true on hit + successful read. False on miss, file corruption, or
// buffer too small.
bool vt_cache_lookup(vt_cache_t * c,
                     const uint8_t hash[VT_CACHE_HASH_BYTES],
                     float * out_embeddings,
                     size_t   out_capacity_floats,
                     int32_t * out_n_tokens,
                     int32_t * out_n_embd);

// Persist an entry. Writes to disk + updates index. May evict older entries
// to stay under budget. Returns false if a single entry exceeds budget or
// if disk write fails.
bool vt_cache_store(vt_cache_t * c,
                    const uint8_t hash[VT_CACHE_HASH_BYTES],
                    const float * embeddings,
                    int32_t n_tokens,
                    int32_t n_embd);

// ── Management ──────────────────────────────────────────────────────────

// Removes all entries from disk + index. Stats are also reset.
void vt_cache_clear(vt_cache_t * c);

// Removes a single entry. Returns true if it was present.
bool vt_cache_remove(vt_cache_t * c, const uint8_t hash[VT_CACHE_HASH_BYTES]);

// Snapshot of current entries. Caller frees with vt_cache_list_free().
vt_cache_entry_info * vt_cache_list(vt_cache_t * c, int32_t * out_count);
void                  vt_cache_list_free(vt_cache_entry_info * entries);

#ifdef __cplusplus
}
#endif
