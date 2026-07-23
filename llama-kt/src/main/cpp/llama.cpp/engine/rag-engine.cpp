#include "rag-engine.h"
#include "llama.h"
#include "common.h"

#include <vector>
#include <string>
#include <unordered_map>
#include <cstring>
#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <algorithm>
#include <new>

// Provided by rag_ingest in the gguf_lib build. Forward-declared here to keep
// rag-engine.cpp free of an unconditional dependency on rag_ingest sources.
extern "C" int  rag_ingest_extract(const uint8_t * bytes, size_t len,
                                   const char * mime_hint, const char * name_hint,
                                   char ** out_text);
extern "C" void rag_ingest_free_string(char * s);

static char * rag_strdup(const char * s) {
    if (!s) return nullptr;
    size_t len = strlen(s);
    char * d = (char *)malloc(len + 1);
    if (d) memcpy(d, s, len + 1);
    return d;
}

struct rag_chunk {
    std::string              text;
    std::string              doc_id;
    int32_t                  chunk_index;
    std::vector<float>       embedding;
    std::vector<uint64_t>    bq_vector;
};

struct rag_document {
    std::string              doc_id;
    int32_t                  first_chunk;
    int32_t                  n_chunks;
};

struct rag_engine {
    rag_engine_params        params;

    llama_model            * model   = nullptr;
    llama_context          * ctx     = nullptr;
    const llama_vocab      * vocab   = nullptr;
    int32_t                  n_embd  = 0;
    std::string              model_fingerprint;

    std::vector<rag_chunk>       chunks;
    std::vector<rag_document>    documents;
    std::unordered_map<std::string, int32_t> doc_index;
};

static std::string rag_compute_model_fingerprint(llama_model * model, int32_t n_embd) {
    char desc[256] = {0};
    llama_model_desc(model, desc, sizeof(desc));
    uint64_t size_b   = llama_model_size(model);
    uint64_t n_params = llama_model_n_params(model);

    char buf[512];
    snprintf(buf, sizeof(buf), "%s|n_embd=%d|size=%llu|nparams=%llu",
        desc, (int)n_embd,
        (unsigned long long)size_b,
        (unsigned long long)n_params);
    return std::string(buf);
}

rag_engine_params rag_engine_default_params(void) {
    return {
        /* n_threads      */ 0,
        /* chunk_size     */ 256,
        /* chunk_overlap  */ 32,
        /* n_dims         */ 256,
        /* top_k          */ 32,
        /* top_n          */ 5,
        /* late_chunking  */ true,
    };
}

rag_engine_t * rag_engine_create(rag_engine_params params) {
    auto * engine = new (std::nothrow) rag_engine();
    if (!engine) return nullptr;
    engine->params = params;
    return engine;
}

void rag_engine_free(rag_engine_t * engine) {
    if (!engine) return;
    if (engine->ctx)   llama_free(engine->ctx);
    if (engine->model) llama_model_free(engine->model);
    delete engine;
}

bool rag_engine_is_loaded(const rag_engine_t * engine) {
    return engine && engine->model && engine->ctx;
}

int32_t rag_engine_document_count(const rag_engine_t * engine) {
    return engine ? (int32_t)engine->documents.size() : 0;
}

int32_t rag_engine_chunk_count(const rag_engine_t * engine) {
    return engine ? (int32_t)engine->chunks.size() : 0;
}

static int32_t rag_load_model_impl(rag_engine_t * engine, llama_model * model) {
    if (!model) return -1;

    if (engine->ctx)   { llama_free(engine->ctx); engine->ctx = nullptr; }
    if (engine->model) { llama_model_free(engine->model); engine->model = nullptr; }

    engine->model = model;
    engine->vocab = llama_model_get_vocab(model);
    engine->n_embd = llama_model_n_embd(model);

    // POOLING_TYPE_NONE for raw per-token embeddings
    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx        = 2048;
    ctx_params.n_batch      = 2048;
    ctx_params.n_ubatch     = 2048;
    ctx_params.n_threads    = engine->params.n_threads > 0 ? engine->params.n_threads : 4;
    ctx_params.embeddings   = true;
    ctx_params.pooling_type = LLAMA_POOLING_TYPE_NONE;

    engine->ctx = llama_init_from_model(model, ctx_params);
    if (!engine->ctx) {
        llama_model_free(engine->model);
        engine->model = nullptr;
        return -1;
    }

    engine->model_fingerprint = rag_compute_model_fingerprint(engine->model, engine->n_embd);

    return 0;
}

// Tuned for embedding models loaded into a process that already hosts a
// causal-LM (the common case: LLM in g_state, then a PDF triggers RAG).
//
//   use_mmap = false
//       The mmap path inside llama.cpp's tensor loader triggered a hard
//       GGML_ABORT in load_tensors when nomic-bert was the second/third
//       model in the same process (verified reproducer: Qwen3-VL + mmproj
//       loaded, then nomic load died ~140 ms after `load_tensors:` with
//       SIGABRT si_code=-1 that bypassed C++ try/catch via pdfium's
//       libunwind tables — see gguf_lib/CLAUDE.md). Reading the file
//       through the normal alloc-then-fread path avoids the bad branch.
//       Cost is small: 84 MB nomic adds ~50 ms vs mmap on a fresh process,
//       paid once per RAG session.
//
//   use_extra_bufts = false
//       Repacking into backend-specific buffer types (KleidiAI / AArch64)
//       is a generation-side perf win. Embeddings encode each chunk once
//       and discard, so the repack tax never pays back, and removing it
//       eliminates one more source of unsupported-quant aborts.
static void rag_apply_embedding_model_params(llama_model_params & p) {
    p.use_mmap        = true;
    p.use_extra_bufts = false;
}

int32_t rag_engine_load_model(rag_engine_t * engine, const char * path) {
    if (!engine || !path) return -1;

    auto model_params = llama_model_default_params();
    rag_apply_embedding_model_params(model_params);
    llama_model * model = llama_model_load_from_file(path, model_params);

    return rag_load_model_impl(engine, model);
}

int32_t rag_engine_load_model_from_fd(rag_engine_t * engine, int fd) {
    if (!engine || fd < 0) return -1;

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);

    auto model_params = llama_model_default_params();
    rag_apply_embedding_model_params(model_params);
    llama_model * model = llama_model_load_from_file(fd_path, model_params);

    return rag_load_model_impl(engine, model);
}

static std::vector<llama_token> rag_tokenize(const rag_engine_t * engine, const char * text) {
    return common_tokenize(engine->vocab, text, true, false);
}

// Encode tokens and extract raw per-token embeddings [n_tokens x n_embd]
static std::vector<float> rag_encode_tokens(
    rag_engine_t * engine,
    const llama_token * tokens, int32_t n_tokens
) {
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);

    for (int32_t i = 0; i < n_tokens; i++) {
        batch.token[i]    = tokens[i];
        batch.pos[i]      = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]   = true;
    }
    batch.n_tokens = n_tokens;

    if (llama_encode(engine->ctx, batch) != 0) {
        llama_batch_free(batch);
        return {};
    }

    std::vector<float> embeddings(n_tokens * engine->n_embd);
    for (int32_t i = 0; i < n_tokens; i++) {
        const float * emb = llama_get_embeddings_ith(engine->ctx, i);
        if (!emb) {
            llama_batch_free(batch);
            return {};
        }
        memcpy(embeddings.data() + i * engine->n_embd, emb, engine->n_embd * sizeof(float));
    }

    llama_batch_free(batch);
    return embeddings;
}

// Mean-pool token embeddings [start, end), truncate to n_dims (Matryoshka), L2-normalize
static std::vector<float> rag_mean_pool_and_truncate(
    const float * token_embeddings, int32_t n_embd,
    int32_t start, int32_t end, int32_t n_dims
) {
    int32_t dims = std::min(n_dims, n_embd);
    std::vector<float> result(dims, 0.0f);
    int32_t count = end - start;
    if (count <= 0) return result;

    for (int32_t i = start; i < end; i++) {
        const float * tok = token_embeddings + i * n_embd;
        for (int32_t d = 0; d < dims; d++) {
            result[d] += tok[d];
        }
    }

    float inv = 1.0f / (float)count;
    for (int32_t d = 0; d < dims; d++) {
        result[d] *= inv;
    }

    // L2 normalize
    float norm = 0.0f;
    for (int32_t d = 0; d < dims; d++) {
        norm += result[d] * result[d];
    }
    norm = sqrtf(norm);
    if (norm > 1e-12f) {
        float inv_norm = 1.0f / norm;
        for (int32_t d = 0; d < dims; d++) {
            result[d] *= inv_norm;
        }
    }

    return result;
}

// Embed a short text (query)
static std::vector<float> rag_embed_text(rag_engine_t * engine, const char * text) {
    auto tokens = rag_tokenize(engine, text);
    if (tokens.empty()) return {};

    auto tok_embs = rag_encode_tokens(engine, tokens.data(), (int32_t)tokens.size());
    if (tok_embs.empty()) return {};

    return rag_mean_pool_and_truncate(
        tok_embs.data(), engine->n_embd,
        0, (int32_t)tokens.size(), engine->params.n_dims
    );
}

// Float vector -> binary vector (threshold at 0)
static std::vector<uint64_t> rag_bq_quantize(const float * vec, int32_t n_dims) {
    int32_t n_words = (n_dims + 63) / 64;
    std::vector<uint64_t> bq(n_words, 0);

    for (int32_t i = 0; i < n_dims; i++) {
        if (vec[i] > 0.0f) {
            bq[i / 64] |= (1ULL << (i % 64));
        }
    }
    return bq;
}

static int32_t rag_hamming_distance(
    const uint64_t * a, const uint64_t * b, int32_t n_words
) {
    int32_t dist = 0;
    for (int32_t i = 0; i < n_words; i++) {
        dist += __builtin_popcountll(a[i] ^ b[i]);
    }
    return dist;
}

// Dot product of L2-normalized vectors = cosine similarity
static float rag_cosine_similarity(const float * a, const float * b, int32_t n_dims) {
    float dot = 0.0f;
    for (int32_t i = 0; i < n_dims; i++) {
        dot += a[i] * b[i];
    }
    return dot;
}

// BQ coarse search: top_k candidates by Hamming distance
static std::vector<std::pair<int32_t, int32_t>> rag_bq_search(
    const std::vector<uint64_t> & query_bq,
    const std::vector<rag_chunk> & chunks,
    int32_t top_k
) {
    int32_t n_words = (int32_t)query_bq.size();
    std::vector<std::pair<int32_t, int32_t>> distances;
    distances.reserve(chunks.size());

    for (int32_t i = 0; i < (int32_t)chunks.size(); i++) {
        int32_t d = rag_hamming_distance(
            query_bq.data(), chunks[i].bq_vector.data(), n_words);
        distances.push_back({d, i});
    }

    int32_t k = std::min(top_k, (int32_t)distances.size());
    std::partial_sort(distances.begin(), distances.begin() + k, distances.end());
    distances.resize(k);
    return distances;
}

// Late chunking: encode full document with sliding windows, then chunk token embeddings
static int32_t rag_index_document_late(
    rag_engine_t * engine, const char * text, const char * doc_id
) {
    auto tokens = rag_tokenize(engine, text);
    if (tokens.empty()) return -1;

    int32_t n_tokens = (int32_t)tokens.size();
    int32_t ctx_window = 2048;
    int32_t window_overlap = 256;

    std::vector<float> all_embs(n_tokens * engine->n_embd, 0.0f);
    std::vector<int32_t> emb_counts(n_tokens, 0);

    for (int32_t start = 0; start < n_tokens; start += (ctx_window - window_overlap)) {
        int32_t end = std::min(start + ctx_window, n_tokens);
        int32_t window_len = end - start;

        auto window_embs = rag_encode_tokens(engine, tokens.data() + start, window_len);
        if (window_embs.empty()) return -1;

        for (int32_t i = 0; i < window_len; i++) {
            int32_t gi = start + i;
            for (int32_t d = 0; d < engine->n_embd; d++) {
                all_embs[gi * engine->n_embd + d] += window_embs[i * engine->n_embd + d];
            }
            emb_counts[gi]++;
        }

        if (end >= n_tokens) break;
    }

    // Average overlapping window regions
    for (int32_t i = 0; i < n_tokens; i++) {
        if (emb_counts[i] > 1) {
            float inv = 1.0f / (float)emb_counts[i];
            for (int32_t d = 0; d < engine->n_embd; d++) {
                all_embs[i * engine->n_embd + d] *= inv;
            }
        }
    }

    int32_t chunk_size    = engine->params.chunk_size;
    int32_t chunk_overlap = engine->params.chunk_overlap;
    int32_t first_chunk   = (int32_t)engine->chunks.size();
    int32_t n_chunks      = 0;

    for (int32_t tok_start = 0; tok_start < n_tokens; tok_start += (chunk_size - chunk_overlap)) {
        int32_t tok_end = std::min(tok_start + chunk_size, n_tokens);

        auto embedding = rag_mean_pool_and_truncate(
            all_embs.data(), engine->n_embd,
            tok_start, tok_end, engine->params.n_dims
        );
        auto bq = rag_bq_quantize(embedding.data(), engine->params.n_dims);

        std::string chunk_text;
        for (int32_t t = tok_start; t < tok_end; t++) {
            chunk_text += common_token_to_piece(engine->ctx, tokens[t]);
        }

        rag_chunk chunk;
        chunk.text        = std::move(chunk_text);
        chunk.doc_id      = doc_id;
        chunk.chunk_index = n_chunks;
        chunk.embedding   = std::move(embedding);
        chunk.bq_vector   = std::move(bq);

        engine->chunks.push_back(std::move(chunk));
        n_chunks++;

        if (tok_end >= n_tokens) break;
    }

    rag_document doc;
    doc.doc_id      = doc_id;
    doc.first_chunk = first_chunk;
    doc.n_chunks    = n_chunks;

    engine->doc_index[doc_id] = (int32_t)engine->documents.size();
    engine->documents.push_back(std::move(doc));

    return n_chunks;
}

// Naive fallback: chunk first, embed each chunk independently
static int32_t rag_index_document_naive(
    rag_engine_t * engine, const char * text, const char * doc_id
) {
    auto tokens = rag_tokenize(engine, text);
    if (tokens.empty()) return -1;

    int32_t n_tokens      = (int32_t)tokens.size();
    int32_t chunk_size    = engine->params.chunk_size;
    int32_t chunk_overlap = engine->params.chunk_overlap;
    int32_t first_chunk   = (int32_t)engine->chunks.size();
    int32_t n_chunks      = 0;

    for (int32_t tok_start = 0; tok_start < n_tokens; tok_start += (chunk_size - chunk_overlap)) {
        int32_t tok_end = std::min(tok_start + chunk_size, n_tokens);
        int32_t window_len = tok_end - tok_start;

        auto tok_embs = rag_encode_tokens(engine, tokens.data() + tok_start, window_len);
        if (tok_embs.empty()) return -1;

        auto embedding = rag_mean_pool_and_truncate(
            tok_embs.data(), engine->n_embd,
            0, window_len, engine->params.n_dims
        );
        auto bq = rag_bq_quantize(embedding.data(), engine->params.n_dims);

        std::string chunk_text;
        for (int32_t t = tok_start; t < tok_end; t++) {
            chunk_text += common_token_to_piece(engine->ctx, tokens[t]);
        }

        rag_chunk chunk;
        chunk.text        = std::move(chunk_text);
        chunk.doc_id      = doc_id;
        chunk.chunk_index = n_chunks;
        chunk.embedding   = std::move(embedding);
        chunk.bq_vector   = std::move(bq);

        engine->chunks.push_back(std::move(chunk));
        n_chunks++;

        if (tok_end >= n_tokens) break;
    }

    rag_document doc;
    doc.doc_id      = doc_id;
    doc.first_chunk = first_chunk;
    doc.n_chunks    = n_chunks;

    engine->doc_index[doc_id] = (int32_t)engine->documents.size();
    engine->documents.push_back(std::move(doc));

    return n_chunks;
}

int32_t rag_engine_add_document(rag_engine_t * engine,
    const char * text, const char * doc_id)
{
    if (!engine || !text || !doc_id) return -1;
    if (!rag_engine_is_loaded(engine)) return -1;

    if (engine->doc_index.count(doc_id)) {
        rag_engine_remove_document(engine, doc_id);
    }

    if (engine->params.late_chunking) {
        return rag_index_document_late(engine, text, doc_id);
    } else {
        return rag_index_document_naive(engine, text, doc_id);
    }
}

int32_t rag_engine_remove_document(rag_engine_t * engine, const char * doc_id) {
    if (!engine || !doc_id) return -1;

    auto it = engine->doc_index.find(doc_id);
    if (it == engine->doc_index.end()) return -1;

    int32_t doc_idx = it->second;
    const auto & doc = engine->documents[doc_idx];

    engine->chunks.erase(
        engine->chunks.begin() + doc.first_chunk,
        engine->chunks.begin() + doc.first_chunk + doc.n_chunks
    );

    for (auto & d : engine->documents) {
        if (d.first_chunk > doc.first_chunk) {
            d.first_chunk -= doc.n_chunks;
        }
    }

    engine->doc_index.erase(it);
    engine->documents.erase(engine->documents.begin() + doc_idx);

    // Only decrement indices for documents that shifted due to the erasure
    for (auto & [id, idx] : engine->doc_index) {
        if (idx > (size_t)doc_idx) idx--;
    }

    return 0;
}

void rag_engine_clear(rag_engine_t * engine) {
    if (!engine) return;
    engine->chunks.clear();
    engine->documents.clear();
    engine->doc_index.clear();
}

// Filtered Hamming top-k against chunks whose doc_id begins with prefix
// (or all chunks if prefix is null/empty).
static std::vector<std::pair<int32_t, int32_t>> rag_bq_search_prefix(
    const std::vector<uint64_t> & query_bq,
    const std::vector<rag_chunk> & chunks,
    int32_t top_k,
    const char * doc_id_prefix
) {
    int32_t n_words = (int32_t)query_bq.size();
    std::vector<std::pair<int32_t, int32_t>> distances;
    distances.reserve(chunks.size());

    bool has_prefix = (doc_id_prefix && doc_id_prefix[0] != '\0');
    size_t prefix_len = has_prefix ? strlen(doc_id_prefix) : 0;

    for (int32_t i = 0; i < (int32_t)chunks.size(); i++) {
        if (has_prefix) {
            const std::string & d = chunks[i].doc_id;
            if (d.size() < prefix_len) continue;
            if (memcmp(d.data(), doc_id_prefix, prefix_len) != 0) continue;
        }
        int32_t d = rag_hamming_distance(
            query_bq.data(), chunks[i].bq_vector.data(), n_words);
        distances.push_back({d, i});
    }

    int32_t k = std::min(top_k, (int32_t)distances.size());
    if (k <= 0) { distances.clear(); return distances; }
    std::partial_sort(distances.begin(), distances.begin() + k, distances.end());
    distances.resize(k);
    return distances;
}

static rag_result * rag_query_impl(rag_engine_t * engine,
    const char * query, const char * doc_id_prefix, int32_t * n_results)
{
    if (!engine || !query || !n_results) return nullptr;
    if (!rag_engine_is_loaded(engine)) return nullptr;
    if (engine->chunks.empty()) { *n_results = 0; return nullptr; }

    auto query_emb = rag_embed_text(engine, query);
    if (query_emb.empty()) return nullptr;

    auto query_bq = rag_bq_quantize(query_emb.data(), engine->params.n_dims);
    auto candidates = rag_bq_search_prefix(
        query_bq, engine->chunks, engine->params.top_k, doc_id_prefix);

    if (candidates.empty()) { *n_results = 0; return nullptr; }

    std::vector<std::pair<float, int32_t>> scored;
    scored.reserve(candidates.size());
    for (auto & [hamming_dist, chunk_idx] : candidates) {
        float score = rag_cosine_similarity(
            query_emb.data(),
            engine->chunks[chunk_idx].embedding.data(),
            engine->params.n_dims
        );
        scored.push_back({score, chunk_idx});
    }

    std::sort(scored.begin(), scored.end(),
        [](const auto & a, const auto & b) { return a.first > b.first; });

    int32_t n = std::min(engine->params.top_n, (int32_t)scored.size());

    auto * results = (rag_result *)malloc(n * sizeof(rag_result));
    if (!results) return nullptr;

    for (int32_t i = 0; i < n; i++) {
        auto & [score, chunk_idx] = scored[i];
        const auto & chunk = engine->chunks[chunk_idx];
        results[i].text        = rag_strdup(chunk.text.c_str());
        results[i].doc_id      = rag_strdup(chunk.doc_id.c_str());
        results[i].chunk_index = chunk.chunk_index;
        results[i].score       = score;
    }

    *n_results = n;
    return results;
}

rag_result * rag_engine_query(rag_engine_t * engine,
    const char * query, int32_t * n_results)
{
    return rag_query_impl(engine, query, nullptr, n_results);
}

rag_result * rag_engine_query_filtered(rag_engine_t * engine,
    const char * query, const char * doc_id_prefix, int32_t * n_results)
{
    return rag_query_impl(engine, query, doc_id_prefix, n_results);
}

void rag_engine_free_results(rag_result * results, int32_t n) {
    if (!results) return;
    for (int32_t i = 0; i < n; i++) {
        free((void *)results[i].text);
        free((void *)results[i].doc_id);
    }
    free(results);
}

char * rag_engine_build_prompt(rag_engine_t * engine,
    const char * query, const char * user_prompt)
{
    if (!engine || !query) return nullptr;

    int32_t n_results = 0;
    auto * results = rag_engine_query(engine, query, &n_results);

    std::string prompt;
    if (n_results > 0 && results) {
        prompt += "Use the following context to answer the question.\n\n";
        prompt += "Context:\n";
        for (int32_t i = 0; i < n_results; i++) {
            prompt += "---\n[";
            prompt += results[i].doc_id;
            prompt += "]\n";
            prompt += results[i].text;
            prompt += "\n";
        }
        prompt += "---\n\n";
        rag_engine_free_results(results, n_results);
    }

    prompt += (user_prompt ? user_prompt : query);

    return rag_strdup(prompt.c_str());
}

char * rag_engine_info_json(const rag_engine_t * engine) {
    if (!engine) return nullptr;

    char buf[1024];
    snprintf(buf, sizeof(buf),
        "{"
        "\"loaded\":%s,"
        "\"n_dims\":%d,"
        "\"chunk_size\":%d,"
        "\"chunk_overlap\":%d,"
        "\"late_chunking\":%s,"
        "\"top_k\":%d,"
        "\"top_n\":%d,"
        "\"n_documents\":%d,"
        "\"n_chunks\":%d,"
        "\"n_embd\":%d,"
        "\"model_n_ctx\":%d"
        "}",
        rag_engine_is_loaded(engine) ? "true" : "false",
        engine->params.n_dims,
        engine->params.chunk_size,
        engine->params.chunk_overlap,
        engine->params.late_chunking ? "true" : "false",
        engine->params.top_k,
        engine->params.top_n,
        (int32_t)engine->documents.size(),
        (int32_t)engine->chunks.size(),
        engine->n_embd,
        engine->ctx ? (int32_t)llama_n_ctx(engine->ctx) : 0
    );

    return rag_strdup(buf);
}

void rag_engine_free_string(char * str) {
    free(str);
}

float * rag_engine_encode(rag_engine_t * engine, const char * text,
                          bool normalize, int32_t * out_n_embd) {
    if (!engine || !engine->ctx || !text || !out_n_embd) return nullptr;
    *out_n_embd = 0;
    auto tokens = rag_tokenize(engine, text);
    if (tokens.empty()) return nullptr;
    auto tok_embs = rag_encode_tokens(engine, tokens.data(), (int32_t)tokens.size());
    if (tok_embs.empty()) return nullptr;
    const int32_t n_embd = engine->n_embd;
    if (n_embd <= 0) return nullptr;
    float * out = (float *)malloc((size_t)n_embd * sizeof(float));
    if (!out) return nullptr;
    for (int32_t d = 0; d < n_embd; d++) out[d] = 0.0f;
    const int32_t n_tok = (int32_t)tokens.size();
    for (int32_t i = 0; i < n_tok; i++) {
        const float * tok = tok_embs.data() + (size_t)i * n_embd;
        for (int32_t d = 0; d < n_embd; d++) out[d] += tok[d];
    }
    const float inv = 1.0f / (float)n_tok;
    for (int32_t d = 0; d < n_embd; d++) out[d] *= inv;
    if (normalize) {
        float norm = 0.0f;
        for (int32_t d = 0; d < n_embd; d++) norm += out[d] * out[d];
        norm = sqrtf(norm);
        if (norm > 1e-12f) {
            const float inv_norm = 1.0f / norm;
            for (int32_t d = 0; d < n_embd; d++) out[d] *= inv_norm;
        }
    }
    *out_n_embd = n_embd;
    return out;
}

void rag_engine_free_floats(float * buf) {
    free(buf);
}

char * rag_engine_extract_text(const uint8_t * bytes, int32_t len,
    const char * mime_hint, const char * name_hint)
{
    if (!bytes || len <= 0) return nullptr;

    char * out = nullptr;
    int rc = rag_ingest_extract(bytes, (size_t)len, mime_hint, name_hint, &out);
    if (rc != 0 || !out) {
        if (out) rag_ingest_free_string(out);
        return nullptr;
    }

    // rag_ingest allocates via std::malloc, rag_engine_free_string calls free,
    // so the caller can free the buffer directly.
    return out;
}

// ============================================================================
// Index export / import
//
// Binary format (little-endian throughout, length-prefixed):
//   [magic 4]        "TNRG"
//   [version 4]      uint32, currently 1
//   [chunk_size 4]   int32
//   [chunk_overlap 4] int32
//   [n_dims 4]       int32
//   [late_chunking 1] uint8 (0/1)
//   [n_embd 4]       int32
//   [fp_len 4] [fp bytes]    model fingerprint
//   [n_docs 4]       int32
//     repeated:
//       [doc_id_len 4] [doc_id bytes]
//       [first_chunk 4] [n_chunks 4]
//   [n_chunks 4]     int32
//     repeated:
//       [doc_id_len 4] [doc_id bytes]
//       [chunk_index 4]
//       [text_len 4] [text bytes]
//       [emb_dim 4]  [emb_dim * float32]
//       [bq_words 4] [bq_words * uint64]
// ============================================================================

#define RAG_EXPORT_MAGIC   0x47524E54u  /* 'TNRG' little-endian */
#define RAG_EXPORT_VERSION 1u

namespace {

struct WriteBuf {
    std::vector<uint8_t> data;
    void put_bytes(const void * p, size_t n) {
        size_t off = data.size();
        data.resize(off + n);
        memcpy(data.data() + off, p, n);
    }
    void put_u32(uint32_t v) { put_bytes(&v, 4); }
    void put_i32(int32_t  v) { put_bytes(&v, 4); }
    void put_u8 (uint8_t  v) { put_bytes(&v, 1); }
    void put_u64(uint64_t v) { put_bytes(&v, 8); }
    void put_lp_str(const std::string & s) {
        put_i32((int32_t)s.size());
        if (!s.empty()) put_bytes(s.data(), s.size());
    }
    void put_lp_bytes(const void * p, int32_t n) {
        put_i32(n);
        if (n > 0) put_bytes(p, (size_t)n);
    }
};

struct ReadBuf {
    const uint8_t * p;
    int32_t         remaining;
    bool            err = false;

    bool take(void * dst, size_t n) {
        if (err || (size_t)remaining < n) { err = true; return false; }
        memcpy(dst, p, n);
        p += n;
        remaining -= (int32_t)n;
        return true;
    }
    bool get_u32(uint32_t & v) { return take(&v, 4); }
    bool get_i32(int32_t  & v) { return take(&v, 4); }
    bool get_u8 (uint8_t  & v) { return take(&v, 1); }
    bool get_u64(uint64_t & v) { return take(&v, 8); }
    bool get_lp_str(std::string & out) {
        int32_t n; if (!get_i32(n) || n < 0) { err = true; return false; }
        if ((size_t)remaining < (size_t)n) { err = true; return false; }
        out.assign(reinterpret_cast<const char *>(p), (size_t)n);
        p += n; remaining -= n;
        return true;
    }
};

}

uint8_t * rag_engine_export_index(const rag_engine_t * engine, int32_t * out_size) {
    if (!engine || !out_size) return nullptr;
    *out_size = 0;

    WriteBuf w;
    w.put_u32(RAG_EXPORT_MAGIC);
    w.put_u32(RAG_EXPORT_VERSION);
    w.put_i32(engine->params.chunk_size);
    w.put_i32(engine->params.chunk_overlap);
    w.put_i32(engine->params.n_dims);
    w.put_u8 (engine->params.late_chunking ? 1 : 0);
    w.put_i32(engine->n_embd);
    w.put_lp_str(engine->model_fingerprint);

    w.put_i32((int32_t)engine->documents.size());
    for (const auto & d : engine->documents) {
        w.put_lp_str(d.doc_id);
        w.put_i32(d.first_chunk);
        w.put_i32(d.n_chunks);
    }

    w.put_i32((int32_t)engine->chunks.size());
    for (const auto & c : engine->chunks) {
        w.put_lp_str(c.doc_id);
        w.put_i32(c.chunk_index);
        w.put_lp_str(c.text);

        w.put_i32((int32_t)c.embedding.size());
        if (!c.embedding.empty()) {
            w.put_bytes(c.embedding.data(),
                c.embedding.size() * sizeof(float));
        }

        w.put_i32((int32_t)c.bq_vector.size());
        if (!c.bq_vector.empty()) {
            w.put_bytes(c.bq_vector.data(),
                c.bq_vector.size() * sizeof(uint64_t));
        }
    }

    size_t total = w.data.size();
    auto * buf = (uint8_t *)malloc(total);
    if (!buf) return nullptr;
    memcpy(buf, w.data.data(), total);
    *out_size = (int32_t)total;
    return buf;
}

void rag_engine_free_buffer(uint8_t * buf) {
    free(buf);
}

int32_t rag_engine_import_index(rag_engine_t * engine,
    const uint8_t * buf, int32_t size)
{
    if (!engine) return -6;
    if (!rag_engine_is_loaded(engine)) return -6;
    if (!buf || size <= 0) return -5;

    ReadBuf r{buf, size};

    uint32_t magic = 0, version = 0;
    if (!r.get_u32(magic))   return -5;
    if (magic != RAG_EXPORT_MAGIC) return -1;
    if (!r.get_u32(version)) return -5;
    if (version != RAG_EXPORT_VERSION) return -2;

    int32_t chunk_size = 0, chunk_overlap = 0, n_dims = 0, n_embd_in = 0;
    uint8_t late_chunking = 0;
    if (!r.get_i32(chunk_size))   return -5;
    if (!r.get_i32(chunk_overlap)) return -5;
    if (!r.get_i32(n_dims))       return -5;
    if (!r.get_u8 (late_chunking)) return -5;
    if (!r.get_i32(n_embd_in))    return -5;

    if (n_dims != engine->params.n_dims) return -3;
    if (n_embd_in != engine->n_embd)     return -3;

    std::string fp;
    if (!r.get_lp_str(fp)) return -5;
    if (fp != engine->model_fingerprint) return -4;

    int32_t n_docs = 0;
    if (!r.get_i32(n_docs) || n_docs < 0) return -5;

    std::vector<rag_document> documents;
    documents.reserve((size_t)n_docs);
    std::unordered_map<std::string, int32_t> doc_index;
    doc_index.reserve((size_t)n_docs);

    for (int32_t i = 0; i < n_docs; i++) {
        rag_document d;
        if (!r.get_lp_str(d.doc_id))   return -5;
        if (!r.get_i32(d.first_chunk)) return -5;
        if (!r.get_i32(d.n_chunks))    return -5;
        doc_index[d.doc_id] = i;
        documents.push_back(std::move(d));
    }

    int32_t n_chunks = 0;
    if (!r.get_i32(n_chunks) || n_chunks < 0) return -5;

    std::vector<rag_chunk> chunks;
    chunks.reserve((size_t)n_chunks);

    for (int32_t i = 0; i < n_chunks; i++) {
        rag_chunk c;
        if (!r.get_lp_str(c.doc_id))   return -5;
        if (!r.get_i32(c.chunk_index)) return -5;
        if (!r.get_lp_str(c.text))     return -5;

        int32_t emb_dim = 0;
        if (!r.get_i32(emb_dim) || emb_dim < 0) return -5;
        if (emb_dim > 0) {
            if ((size_t)r.remaining < (size_t)emb_dim * sizeof(float)) return -5;
            c.embedding.resize((size_t)emb_dim);
            if (!r.take(c.embedding.data(), (size_t)emb_dim * sizeof(float))) return -5;
        }

        int32_t bq_words = 0;
        if (!r.get_i32(bq_words) || bq_words < 0) return -5;
        if (bq_words > 0) {
            if ((size_t)r.remaining < (size_t)bq_words * sizeof(uint64_t)) return -5;
            c.bq_vector.resize((size_t)bq_words);
            if (!r.take(c.bq_vector.data(), (size_t)bq_words * sizeof(uint64_t))) return -5;
        }

        chunks.push_back(std::move(c));
    }

    if (r.err) return -5;

    // Adopt restored params (don't override n_threads / top_k / top_n)
    engine->params.chunk_size    = chunk_size;
    engine->params.chunk_overlap = chunk_overlap;
    engine->params.late_chunking = (late_chunking != 0);

    engine->chunks    = std::move(chunks);
    engine->documents = std::move(documents);
    engine->doc_index = std::move(doc_index);

    return 0;
}
