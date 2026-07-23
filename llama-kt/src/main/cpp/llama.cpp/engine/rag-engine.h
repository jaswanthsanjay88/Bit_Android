#pragma once

// RAG engine: late-chunking retrieval with binary-quantized embeddings

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct rag_engine rag_engine_t;

typedef struct {
    int32_t  n_threads;           // 0 = auto
    int32_t  chunk_size;          // tokens per chunk (default 256)
    int32_t  chunk_overlap;       // overlap tokens (default 32)
    int32_t  n_dims;              // Matryoshka dim: 768/512/256/128 (default 256)
    int32_t  top_k;               // BQ search candidates (default 32)
    int32_t  top_n;               // final results after re-rank (default 5)
    bool     late_chunking;       // embed full doc then chunk (default true)
} rag_engine_params;

typedef struct {
    const char * text;
    const char * doc_id;
    int32_t      chunk_index;
    float        score;
} rag_result;

// Lifecycle
rag_engine_params  rag_engine_default_params(void);
rag_engine_t *     rag_engine_create(rag_engine_params params);
void               rag_engine_free(rag_engine_t * engine);

// Embedding model
int32_t  rag_engine_load_model(rag_engine_t * engine, const char * path);
int32_t  rag_engine_load_model_from_fd(rag_engine_t * engine, int fd);
bool     rag_engine_is_loaded(const rag_engine_t * engine);

// Indexing
int32_t  rag_engine_add_document(rag_engine_t * engine,
             const char * text, const char * doc_id);
int32_t  rag_engine_remove_document(rag_engine_t * engine,
             const char * doc_id);
void     rag_engine_clear(rag_engine_t * engine);
int32_t  rag_engine_document_count(const rag_engine_t * engine);
int32_t  rag_engine_chunk_count(const rag_engine_t * engine);

// Retrieval
rag_result * rag_engine_query(rag_engine_t * engine,
                 const char * query, int32_t * n_results);

// Top-N results restricted to chunks whose doc_id starts with doc_id_prefix.
// If doc_id_prefix is null or empty, behaves like rag_engine_query.
rag_result * rag_engine_query_filtered(rag_engine_t * engine,
                 const char * query, const char * doc_id_prefix,
                 int32_t * n_results);

void         rag_engine_free_results(rag_result * results, int32_t n);

// Convenience: query + format into augmented prompt
char *       rag_engine_build_prompt(rag_engine_t * engine,
                 const char * query, const char * user_prompt);

// Embed a text string into a raw mean-pooled embedding at the model's native
// n_embd (no Matryoshka truncation, no BQ quantization). Optionally L2
// normalizes. Caller frees the returned buffer via rag_engine_free_floats.
// Returns NULL on tokenize/encode failure; sets *out_n_embd on success.
float *      rag_engine_encode(rag_engine_t * engine,
                 const char * text, bool normalize, int32_t * out_n_embd);
void         rag_engine_free_floats(float * buf);

// Info
char *       rag_engine_info_json(const rag_engine_t * engine);
void         rag_engine_free_string(char * str);

// Standalone text extraction for PDF/DOCX/EPUB/HTML/text-like bytes.
// Returns malloc'd null-terminated UTF-8 string (caller frees via
// rag_engine_free_string), or NULL on parse failure / unsupported / empty.
char *       rag_engine_extract_text(const uint8_t * bytes, int32_t len,
                 const char * mime_hint, const char * name_hint);

// Serialize the in-memory index (chunks, BQ vectors, float embeddings, doc
// metadata) to a portable byte buffer. Caller frees with
// rag_engine_free_buffer. Returns NULL on error; sets *out_size on success.
uint8_t *    rag_engine_export_index(const rag_engine_t * engine, int32_t * out_size);
void         rag_engine_free_buffer(uint8_t * buf);

// Import a buffer produced by rag_engine_export_index. Engine must be
// created and embedding model loaded (for fingerprint compatibility check).
// Replaces any in-memory index. Returns 0 on success, or:
//   -1 magic mismatch, -2 version mismatch, -3 dim mismatch,
//   -4 model fingerprint mismatch, -5 corrupt buffer, -6 engine not ready.
int32_t      rag_engine_import_index(rag_engine_t * engine,
                 const uint8_t * buf, int32_t size);

#ifdef __cplusplus
}
#endif
