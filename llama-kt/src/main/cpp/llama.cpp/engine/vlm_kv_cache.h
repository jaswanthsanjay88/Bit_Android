// VLM KV cache — content-addressed store for the LLM context state captured at
// the post-image-chunk boundary during VLM prompt-eval. Lets us skip both the
// vision encoder AND the ~9s image-prefill `llama_decode` on repeat queries
// against the same image (with a stable system prompt + chat template prefix).
//
// Pairs with vt_cache:
//   vt_cache     — ViT embeddings (skips vision encode)
//   vlm_kv_cache — LLM context state after image was decoded (skips both)
//
// Storage layout:
//   {dir}/{hex(hash)}.vkv    — one file per cached entry
//
// Each .vkv file is:
//   vlm_kv_entry_header { magic, version, n_tokens, blob_size, reserved }
//   uint8_t[blob_size]       — opaque payload from llama_state_seq_get_data
//
// Hash is opaque to this layer — caller derives it from
// (image bytes ∥ projector path ∥ image_max_tokens ∥ model fingerprint
//  ∥ system prompt ∥ chat template prefix). The cache trusts the caller.
//
// Eviction: LRU by last_access_ms. Triggered on store when total > budget.
//
// Thread safety: every public function takes the cache's internal mutex.

#pragma once

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VLM_KV_CACHE_HASH_BYTES 32

typedef struct vlm_kv_cache vlm_kv_cache_t;

typedef struct {
    uint8_t  hash[VLM_KV_CACHE_HASH_BYTES];
    int32_t  n_tokens;
    int64_t  size_bytes;
    int64_t  last_access_ms;
} vlm_kv_cache_entry_info;

// ── Lifecycle ───────────────────────────────────────────────────────────

vlm_kv_cache_t * vlm_kv_cache_create(const char * dir, int64_t budget_bytes);
void             vlm_kv_cache_free  (vlm_kv_cache_t * c);

// ── Configuration ───────────────────────────────────────────────────────

void    vlm_kv_cache_set_budget (vlm_kv_cache_t * c, int64_t bytes);
int64_t vlm_kv_cache_get_budget (const vlm_kv_cache_t * c);

// ── Stats ───────────────────────────────────────────────────────────────

int64_t vlm_kv_cache_total_bytes(const vlm_kv_cache_t * c);
int32_t vlm_kv_cache_count      (const vlm_kv_cache_t * c);
int64_t vlm_kv_cache_hits       (const vlm_kv_cache_t * c);
int64_t vlm_kv_cache_misses     (const vlm_kv_cache_t * c);
void    vlm_kv_cache_reset_stats(vlm_kv_cache_t * c);

// ── Lookup / store ──────────────────────────────────────────────────────

// Cheap header-only check. Does NOT touch LRU. Use to query whether an entry
// exists and how big the blob is.
bool vlm_kv_cache_peek(vlm_kv_cache_t * c,
                       const uint8_t hash[VLM_KV_CACHE_HASH_BYTES],
                       int32_t * out_n_tokens,
                       size_t  * out_blob_size);

// Read the KV blob into caller-provided buffer. Touches LRU on hit.
//
// out_capacity must be >= the blob size from peek(). Use peek() first if you
// don't know the size.
//
// Returns true on hit + successful read. False on miss, file corruption, or
// buffer too small.
bool vlm_kv_cache_lookup(vlm_kv_cache_t * c,
                         const uint8_t hash[VLM_KV_CACHE_HASH_BYTES],
                         uint8_t * out_blob,
                         size_t    out_capacity,
                         size_t  * out_size,
                         int32_t * out_n_tokens);

// Persist an entry. Writes to disk + updates index. May evict older entries
// to stay under budget. Returns false if a single entry exceeds budget or
// if disk write fails.
bool vlm_kv_cache_store(vlm_kv_cache_t * c,
                        const uint8_t hash[VLM_KV_CACHE_HASH_BYTES],
                        const uint8_t * blob,
                        size_t          blob_size,
                        int32_t         n_tokens);

// ── Management ──────────────────────────────────────────────────────────

void vlm_kv_cache_clear (vlm_kv_cache_t * c);
bool vlm_kv_cache_remove(vlm_kv_cache_t * c,
                         const uint8_t hash[VLM_KV_CACHE_HASH_BYTES]);

vlm_kv_cache_entry_info * vlm_kv_cache_list(vlm_kv_cache_t * c, int32_t * out_count);
void                      vlm_kv_cache_list_free(vlm_kv_cache_entry_info * entries);

#ifdef __cplusplus
}
#endif
