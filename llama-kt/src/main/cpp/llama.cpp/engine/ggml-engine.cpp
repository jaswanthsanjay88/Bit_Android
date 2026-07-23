#include "ggml-engine-internal.h"
#include "thread-engine.h"
#include "tn-log.h"

#include <sstream>
#include <thread>
#include <cstring>

// Forward declarations for functions defined after the generate loop
static void kv_evict(ggml_engine_t * engine);

// Non-static shim so the inline ggml_engine_generate_loop in
// ggml-engine-internal.h can call kv_evict from other translation units.
void ggml_engine_kv_evict_internal(ggml_engine_t * engine) {
    kv_evict(engine);
}

#ifdef __ANDROID__
#include <sys/sysinfo.h>
#include <unistd.h>
#include <sched.h>
#endif

ggml_engine_params ggml_engine_default_params(void) {
    ggml_engine_params p{};
    p.n_ctx            = 0;
    p.n_batch          = 0; // 0 = set automatically by thread_mode
    p.n_threads        = 0; // 0 = set automatically by thread_mode
    p.n_threads_batch  = 0; // 0 = set automatically by thread_mode
    p.use_mmap         = true;
    p.use_mlock        = false;
    p.n_gpu_layers     = 0;
    p.rope_freq_base   = 0.0f;
    p.rope_freq_scale  = 0.0f;
    p.flash_attn       = true;
    p.thread_mode      = TN_THREAD_BALANCED;
    return p;
}

ggml_engine_sampling ggml_engine_default_sampling(void) {
    ggml_engine_sampling s{};
    s.temperature       = 0.7f;
    s.top_k             = 40;
    s.top_p             = 0.95f;
    s.min_p             = 0.05f;
    s.repeat_penalty    = 1.1f;
    s.repeat_last_n     = 64;
    s.frequency_penalty = 0.0f;
    s.presence_penalty  = 0.0f;
    s.seed              = 0xFFFFFFFF;
    s.n_predict         = 256;
    s.stop_sequence_count = 0;
    memset(s.stop_sequences, 0, sizeof(s.stop_sequences));
    return s;
}

ggml_engine_t * ggml_engine_create(ggml_engine_params params) {
    llama_backend_init();

    auto * engine = new ggml_engine();
    engine->params = params;

    if (params.thread_mode >= 0 && params.thread_mode <= 2) {
        tn_thread_config tcfg = tn_thread_config_for_mode((tn_thread_mode)params.thread_mode);
        if (engine->params.n_threads <= 0)
            engine->params.n_threads = tcfg.n_threads_generation;
        if (engine->params.n_threads_batch <= 0)
            engine->params.n_threads_batch = tcfg.n_threads_batch;
        if (engine->params.n_batch <= 0)
            engine->params.n_batch = tcfg.n_batch;
        engine->thread_cfg = tcfg;
    } else {
        // Manual mode: fallback to 75% of cores
        if (engine->params.n_threads <= 0) {
            int n = (int)std::thread::hardware_concurrency();
            engine->params.n_threads = n > 0 ? (n * 3) / 4 : 4;
        }
        if (engine->params.n_threads_batch <= 0)
            engine->params.n_threads_batch = engine->params.n_threads;
    }

    return engine;
}

void ggml_engine_free(ggml_engine_t * engine) {
    if (!engine) return;
    ggml_engine_unload_model(engine);
    delete engine;
}

ggml_engine_status ggml_engine_load_model(ggml_engine_t * engine, const char * path) {
    if (!engine || !path) return GGML_ENGINE_ERROR_LOAD_FAILED;

    ggml_engine_unload_model(engine);

    auto mparams = llama_model_default_params();
    mparams.use_mmap  = engine->params.use_mmap;
    mparams.use_mlock = engine->params.use_mlock;

    engine->model = llama_model_load_from_file(path, mparams);
    if (!engine->model) {
        return GGML_ENGINE_ERROR_LOAD_FAILED;
    }

    engine->vocab = llama_model_get_vocab(engine->model);

    auto cparams = llama_context_default_params();
    cparams.n_ctx           = engine->params.n_ctx > 0 ? engine->params.n_ctx : 0;
    cparams.n_batch         = engine->params.n_batch;
    cparams.n_threads       = engine->params.n_threads;
    cparams.n_threads_batch = engine->params.n_threads_batch;
    if (engine->params.flash_attn) {
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }

    if (engine->params.rope_freq_base > 0.0f) {
        cparams.rope_freq_base = engine->params.rope_freq_base;
    }
    if (engine->params.rope_freq_scale > 0.0f) {
        cparams.rope_freq_scale = engine->params.rope_freq_scale;
    }

    engine->ctx = llama_init_from_model(engine->model, cparams);
    if (!engine->ctx) {
        llama_model_free(engine->model);
        engine->model = nullptr;
        engine->vocab = nullptr;
        return GGML_ENGINE_ERROR_CONTEXT_FAIL;
    }

    // Allocate prompt-processing batch once at model load time
    if (engine->prompt_batch_ready) {
        llama_batch_free(engine->prompt_batch);
    }
    engine->prompt_batch = llama_batch_init(engine->params.n_batch, 0, 1);
    engine->prompt_batch_ready = true;

#if defined(__linux__) || defined(__ANDROID__)
    if (engine->thread_cfg.pin_to_perf_cores && engine->thread_cfg.n_perf_core_ids > 0) {
        TN_LOG_INF("pinning %d perf cores for inference", engine->thread_cfg.n_perf_core_ids);
    }
#endif

    engine->n_past = 0;
    return GGML_ENGINE_OK;
}

ggml_engine_status ggml_engine_load_model_from_fd(ggml_engine_t * engine, int fd) {
    if (!engine || fd < 0) return GGML_ENGINE_ERROR_LOAD_FAILED;

    // Android SAF: access file via /proc/self/fd/<fd>
    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);

    return ggml_engine_load_model(engine, fd_path);
}

void ggml_engine_unload_model(ggml_engine_t * engine) {
    if (!engine) return;

    if (engine->ctx) {
        llama_free(engine->ctx);
        engine->ctx = nullptr;
    }
    if (engine->model) {
        llama_model_free(engine->model);
        engine->model = nullptr;
    }
    if (engine->prompt_batch_ready) {
        llama_batch_free(engine->prompt_batch);
        engine->prompt_batch_ready = false;
    }
    if (engine->batch_ready) {
        llama_batch_free(engine->batch);
        engine->batch_ready = false;
    }
    engine->vocab = nullptr;
    engine->n_past = 0;
    engine->response.clear();
}

bool ggml_engine_is_loaded(const ggml_engine_t * engine) {
    return engine && engine->model && engine->ctx;
}

char * ggml_engine_model_info_json(const ggml_engine_t * engine) {
    if (!engine || !engine->model) return strdup_alloc("{}");

    const auto * model = engine->model;
    const auto * vocab = engine->vocab;

    char desc[256] = {0};
    llama_model_desc(model, desc, sizeof(desc));

    std::ostringstream json;
    json << "{\n";
    json << "  \"description\": \"" << desc << "\",\n";
    json << "  \"size_bytes\": " << llama_model_size(model) << ",\n";
    json << "  \"n_params\": " << llama_model_n_params(model) << ",\n";
    json << "  \"n_embd\": " << llama_model_n_embd(model) << ",\n";
    json << "  \"n_layer\": " << llama_model_n_layer(model) << ",\n";
    json << "  \"n_head\": " << llama_model_n_head(model) << ",\n";
    json << "  \"n_head_kv\": " << llama_model_n_head_kv(model) << ",\n";
    json << "  \"n_ctx_train\": " << llama_model_n_ctx_train(model) << ",\n";
    json << "  \"n_vocab\": " << llama_vocab_n_tokens(vocab) << ",\n";
    json << "  \"has_encoder\": " << (llama_model_has_encoder(model) ? "true" : "false") << ",\n";
    json << "  \"has_decoder\": " << (llama_model_has_decoder(model) ? "true" : "false") << ",\n";
    json << "  \"is_recurrent\": " << (llama_model_is_recurrent(model) ? "true" : "false") << ",\n";

    if (engine->ctx) {
        json << "  \"n_ctx\": " << llama_n_ctx(engine->ctx) << ",\n";
        json << "  \"n_batch\": " << llama_n_batch(engine->ctx) << ",\n";
    }

    json << "  \"metadata\": {\n";
    int n_meta = llama_model_meta_count(model);
    for (int i = 0; i < n_meta; i++) {
        char key[256] = {0};
        char val[512] = {0};
        llama_model_meta_key_by_index(model, i, key, sizeof(key));
        llama_model_meta_val_str_by_index(model, i, val, sizeof(val));

        std::string escaped_val;
        for (const char * p = val; *p; p++) {
            if (*p == '"') escaped_val += "\\\"";
            else if (*p == '\\') escaped_val += "\\\\";
            else if (*p == '\n') escaped_val += "\\n";
            else escaped_val += *p;
        }

        json << "    \"" << key << "\": \"" << escaped_val << "\"";
        if (i < n_meta - 1) json << ",";
        json << "\n";
    }
    json << "  }\n";
    json << "}";

    return strdup_alloc(json.str());
}

void ggml_engine_free_string(char * str) {
    free(str);
}

static ggml_engine_status ggml_engine_generate_impl(
    ggml_engine_t * engine,
    const char * prompt,
    ggml_engine_sampling sampling,
    ggml_engine_token_callback callback,
    void * user_data,
    bool clear_kv
) {
    if (!engine || !engine->model || !engine->ctx) {
        return GGML_ENGINE_ERROR_NO_MODEL;
    }

    engine->cancelled.store(false);
    engine->response.clear();
    memset(&engine->perf, 0, sizeof(engine->perf));

    const int n_ctx = llama_n_ctx(engine->ctx);

    std::vector<llama_token> tokens = common_tokenize(engine->vocab, prompt, true, true);
    int n_prompt = (int)tokens.size();

    if (n_prompt < 1) {
        return GGML_ENGINE_ERROR_TOKENIZE;
    }

    if (clear_kv) {
        llama_memory_t mem = llama_get_memory(engine->ctx);
        if (mem) {
            llama_memory_clear(mem, true);
        }
        engine->n_past = 0;
    }

    // If prompt won't fit and we have an eviction policy, apply it before failing.
    {
        const ggml_engine_kv_policy & p = engine->kv_policy;
        if (p.n_window > 0 && p.evict_at_full) {
            int need = n_prompt + (p.n_window / 4); // keep headroom for generation
            if (engine->n_past + need > n_ctx) {
                kv_evict(engine);
            }
        }
    }

    int remaining = n_ctx - engine->n_past;
    if (n_prompt > remaining) {
        return GGML_ENGINE_ERROR_OUT_OF_MEM;
    }

    int space_after_prompt = remaining - n_prompt;
    if (sampling.n_predict < 0) {
        sampling.n_predict = space_after_prompt;
    } else {
        sampling.n_predict = std::min(sampling.n_predict, space_after_prompt);
    }

    int64_t t_prompt_start = llama_time_us();

    // Use cached prompt batch — already allocated on model load
    struct llama_batch & batch = engine->prompt_batch;

    for (int i = 0; i < n_prompt; i += engine->params.n_batch) {
        int n_eval = std::min(engine->params.n_batch, n_prompt - i);

        common_batch_clear(batch);
        for (int j = 0; j < n_eval; j++) {
            common_batch_add(batch, tokens[i + j], engine->n_past + j, {0}, (i + j == n_prompt - 1));
        }

        if (llama_decode(engine->ctx, batch) != 0) {
            return GGML_ENGINE_ERROR_DECODE;
        }
        engine->n_past += n_eval;
    }

    int64_t t_prompt_end = llama_time_us();
    engine->perf.prompt_eval_ms = (t_prompt_end - t_prompt_start) / 1000.0;
    engine->perf.prompt_tokens = n_prompt;

    return ggml_engine_generate_loop(engine, sampling, callback, user_data);
}

ggml_engine_status ggml_engine_generate(
    ggml_engine_t * engine,
    const char * prompt,
    ggml_engine_sampling sampling,
    ggml_engine_token_callback callback,
    void * user_data
) {
    return ggml_engine_generate_impl(engine, prompt, sampling, callback, user_data, true);
}

ggml_engine_status ggml_engine_generate_continue(
    ggml_engine_t * engine,
    const char * prompt,
    ggml_engine_sampling sampling,
    ggml_engine_token_callback callback,
    void * user_data
) {
    return ggml_engine_generate_impl(engine, prompt, sampling, callback, user_data, false);
}

void ggml_engine_cancel(ggml_engine_t * engine) {
    if (engine) {
        engine->cancelled.store(true);
    }
}

char * ggml_engine_get_response(const ggml_engine_t * engine) {
    if (!engine) return strdup_alloc("");
    return strdup_alloc(engine->response);
}

void ggml_engine_clear_context(ggml_engine_t * engine) {
    if (!engine || !engine->ctx) return;

    llama_memory_t mem = llama_get_memory(engine->ctx);
    if (mem) {
        llama_memory_clear(mem, true);
    }
    engine->n_past = 0;
    engine->response.clear();
}

// ── KV Session Save / Load ────────────────────────────────────────────────────

// Header written at the start of every session file for version safety.
static const uint32_t KV_SESSION_MAGIC   = 0x544E4B56; // "TNKV"
static const uint32_t KV_SESSION_VERSION = 1;

bool ggml_engine_save_session(ggml_engine_t * engine, const char * path) {
    if (!engine || !engine->ctx || !path) return false;

    // llama_state_save_file requires the token list that was evaluated.
    // We pass n_past as a sentinel count of zeros — the important state is
    // the KV cache; callers should re-feed the prompt if they need logits.
    std::vector<llama_token> dummy_tokens(engine->n_past, 0);

    if (!llama_state_save_file(engine->ctx, path,
                               dummy_tokens.data(), (size_t)engine->n_past)) {
        TN_LOG_ERR("save_session: llama_state_save_file failed: %s", path);
        return false;
    }

    // Append our own header (n_past) after the llama state blob.
    FILE * f = fopen(path, "ab");
    if (!f) return false;
    fwrite(&KV_SESSION_MAGIC,   sizeof(uint32_t), 1, f);
    fwrite(&KV_SESSION_VERSION, sizeof(uint32_t), 1, f);
    int32_t np = engine->n_past;
    fwrite(&np, sizeof(int32_t), 1, f);
    fclose(f);

    TN_LOG_INF("session saved: %s (n_past=%d)", path, engine->n_past);
    return true;
}

bool ggml_engine_load_session(ggml_engine_t * engine, const char * path) {
    if (!engine || !engine->ctx || !path) return false;

    // Read our trailer from the end of the file to recover n_past.
    static const size_t TRAILER_SIZE = sizeof(uint32_t) * 2 + sizeof(int32_t);
    FILE * f = fopen(path, "rb");
    if (!f) { TN_LOG_ERR("load_session: cannot open %s", path); return false; }

    if (fseek(f, -(long)TRAILER_SIZE, SEEK_END) != 0) { fclose(f); return false; }

    uint32_t magic = 0, version = 0;
    int32_t  n_past_saved = 0;
    fread(&magic,        sizeof(uint32_t), 1, f);
    fread(&version,      sizeof(uint32_t), 1, f);
    fread(&n_past_saved, sizeof(int32_t),  1, f);
    fclose(f);

    if (magic != KV_SESSION_MAGIC || version != KV_SESSION_VERSION) {
        TN_LOG_ERR("load_session: bad magic/version in %s", path);
        return false;
    }

    // Load the llama KV state — fills the context's KV cache.
    std::vector<llama_token> dummy(n_past_saved, 0);
    size_t token_count_out = 0;
    if (!llama_state_load_file(engine->ctx, path,
                               dummy.data(), (size_t)n_past_saved,
                               &token_count_out)) {
        TN_LOG_ERR("load_session: llama_state_load_file failed: %s", path);
        return false;
    }

    engine->n_past = n_past_saved;
    TN_LOG_INF("session loaded: %s (n_past=%d)", path, engine->n_past);
    return true;
}

// ── KV Eviction (StreamingLLM + post-prefill budget) ─────────────────────────

ggml_engine_kv_policy ggml_engine_default_kv_policy(void) {
    ggml_engine_kv_policy p = {};
    p.n_sink        = 4;
    p.n_window      = 0;    // disabled by default
    p.evict_at_full = true;
    return p;
}

void ggml_engine_set_kv_policy(ggml_engine_t * engine, ggml_engine_kv_policy policy) {
    if (!engine) return;
    engine->kv_policy = policy;
    TN_LOG_INF("KV policy: sink=%d window=%d evict_at_full=%d",
                policy.n_sink, policy.n_window, (int)policy.evict_at_full);
}

// Internal: evict tokens outside [0, n_sink) ∪ [n_past - n_window, n_past).
// Positions of the kept tail are shifted down to sit immediately after the sink.
// Result: n_past = n_sink + min(old_n_past - n_sink, n_window).
static void kv_evict(ggml_engine_t * engine) {
    const ggml_engine_kv_policy & p = engine->kv_policy;
    if (p.n_window <= 0) return;

    llama_memory_t mem = llama_get_memory(engine->ctx);
    if (!mem) return;

    int n_sink   = p.n_sink;
    int n_window = p.n_window;
    int n_past   = engine->n_past;

    // Nothing to evict if we're within budget
    if (n_past <= n_sink + n_window) return;

    // Range of tokens to remove: [n_sink, n_past - n_window)
    llama_pos evict_from = (llama_pos)n_sink;
    llama_pos evict_to   = (llama_pos)(n_past - n_window);

    if (!llama_memory_seq_rm(mem, 0, evict_from, evict_to)) {
        TN_LOG_WRN("kv_evict: seq_rm failed (partial seq?)");
        return;
    }

    // Shift the kept tail positions down so they're contiguous with the sink.
    // Tail was at [n_past - n_window, n_past), move to [n_sink, n_sink + n_window).
    llama_pos delta = -(evict_to - evict_from);
    llama_memory_seq_add(mem, 0, evict_to, (llama_pos)n_past, delta);

    engine->n_past = n_sink + n_window;
    TN_LOG_INF("kv evict: removed [%d,%d), n_past %d→%d",
                (int)evict_from, (int)evict_to, n_past, engine->n_past);
}

void ggml_engine_evict_to_budget(ggml_engine_t * engine) {
    if (!engine || !engine->ctx) return;
    kv_evict(engine);
}

int32_t ggml_engine_context_used(const ggml_engine_t * engine) {
    if (!engine) return 0;
    return engine->n_past;
}

int32_t ggml_engine_context_size(const ggml_engine_t * engine) {
    if (!engine || !engine->ctx) return 0;
    return llama_n_ctx(engine->ctx);
}

int32_t ggml_engine_context_remaining(const ggml_engine_t * engine) {
    if (!engine || !engine->ctx) return 0;
    return llama_n_ctx(engine->ctx) - engine->n_past;
}

ggml_engine_context_info ggml_engine_context_status(const ggml_engine_t * engine,
                                                     const char * prompt) {
    ggml_engine_context_info info = {};
    if (!engine || !engine->ctx) return info;

    info.total     = llama_n_ctx(engine->ctx);
    info.used      = engine->n_past;
    info.remaining = info.total - info.used;

    if (prompt && engine->vocab) {
        // Use llama_tokenize directly to avoid a full vector allocation when we only need the count
        int32_t n = llama_tokenize(engine->vocab, prompt, (int32_t)strlen(prompt),
                                   nullptr, 0, true, true);
        info.prompt_estimate = n > 0 ? n : 0;
        info.after_prompt    = info.remaining - info.prompt_estimate;
        if (info.after_prompt < 0) info.after_prompt = 0;
    } else {
        info.prompt_estimate = -1;
        info.after_prompt    = -1;
    }

    return info;
}

int32_t ggml_engine_tokenize(const ggml_engine_t * engine,
                              const char * text, int32_t * tokens, int32_t max_tokens) {
    if (!engine || !engine->vocab || !text || !tokens) return -1;

    std::vector<llama_token> tok = common_tokenize(engine->vocab, text, false, false);
    int32_t n = (int32_t)tok.size();
    int32_t copy = n < max_tokens ? n : max_tokens;
    memcpy(tokens, tok.data(), copy * sizeof(int32_t));
    return n;
}

char * ggml_engine_detokenize(const ggml_engine_t * engine,
                               const int32_t * tokens, int32_t n_tokens) {
    if (!engine || !engine->vocab || !tokens || n_tokens <= 0) {
        return strdup_alloc("");
    }

    std::string result;
    for (int32_t i = 0; i < n_tokens; i++) {
        char buf[256];
        int n = llama_token_to_piece(engine->vocab, tokens[i], buf, sizeof(buf), 0, true);
        if (n > 0) {
            result.append(buf, n);
        }
    }
    return strdup_alloc(result);
}


ggml_engine_perf ggml_engine_get_perf(const ggml_engine_t * engine) {
    if (!engine) {
        return {};
    }
    return engine->perf;
}

void ggml_engine_set_thread_mode(ggml_engine_t * engine, int32_t mode) {
    if (!engine || mode < 0 || mode > 2) return;

    tn_thread_config tcfg = tn_thread_config_for_mode((tn_thread_mode)mode);
    engine->params.thread_mode = mode;
    engine->params.n_threads = tcfg.n_threads_generation;
    engine->params.n_threads_batch = tcfg.n_threads_batch;
    engine->params.n_batch = tcfg.n_batch;
    engine->thread_cfg = tcfg;

    // Update live context if model is loaded
    if (engine->ctx) {
        llama_set_n_threads(engine->ctx, tcfg.n_threads_generation, tcfg.n_threads_batch);
    }

    TN_LOG_INF("thread mode set to %d (gen=%d, batch=%d)",
               mode, tcfg.n_threads_generation, tcfg.n_threads_batch);
}

ggml_engine_device_info ggml_engine_get_device_info(void) {
    tn_device_info dev = tn_detect_device();
    ggml_engine_device_info info;
    info.n_cores_total      = dev.n_cores_total;
    info.n_perf_cores       = dev.n_perf_cores;
    info.n_efficiency_cores = dev.n_efficiency_cores;
    info.max_freq_khz       = dev.max_freq_khz;
    info.min_freq_khz       = dev.min_freq_khz;
    return info;
}

int64_t ggml_engine_available_ram(void) {
    return tn_available_ram_bytes();
}

int64_t ggml_engine_max_model_size(int64_t available_ram, int32_t n_ctx) {
    return tn_max_model_size(available_ram, n_ctx);
}

int32_t ggml_engine_recommend_batch(int64_t model_size_bytes) {
    return tn_recommend_batch_size(model_size_bytes);
}

void tn_engine_set_log_callback(tn_engine_log_callback cb, void * user_data) {
    tn_log_set_callback(reinterpret_cast<tn_log_callback>(cb), user_data);
}

void tn_engine_set_log_level(tn_engine_log_level max_level) {
    tn_log_set_level(static_cast<tn_log_level>(max_level));
}
