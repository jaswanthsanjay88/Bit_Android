#pragma once

#include "ggml-engine.h"
#include "thread-engine.h"
#include "engine-utils.h"
#include "llama.h"
#include "common.h"
#include "sampling.h"

#include <string>
#include <vector>
#include <atomic>
#include <cstdio>

struct ggml_engine {
    ggml_engine_params   params;
    tn_thread_config     thread_cfg{};
    struct llama_model  * model   = nullptr;
    struct llama_context * ctx    = nullptr;
    const struct llama_vocab * vocab = nullptr;

    std::string           response;
    std::atomic<bool>     cancelled{false};
    ggml_engine_perf      perf{};

    int32_t               n_past = 0;

    // KV eviction policy (StreamingLLM + post-prefill budget)
    ggml_engine_kv_policy kv_policy{};

    // Single-token batch for generation loop — allocated once, reused across calls
    struct llama_batch    batch{};
    bool                  batch_ready = false;

    // Prompt-processing batch (capacity n_batch) — allocated on model load, reused
    struct llama_batch    prompt_batch{};
    bool                  prompt_batch_ready = false;
};

// Ensure the cached single-token batch is allocated for this engine.
static inline void ggml_engine_ensure_batch(ggml_engine_t * engine) {
    if (!engine->batch_ready) {
        engine->batch = llama_batch_init(1, 0, 1);
        engine->batch_ready = true;
    }
}

// Defined in ggml-engine.cpp. Declared here so TUs that include the inline
// ggml_engine_generate_loop below can resolve the call.
void ggml_engine_kv_evict_internal(ggml_engine_t * engine);

// Autoregressive decode loop from current n_past. Expects logits ready at (n_past - 1).
static inline ggml_engine_status ggml_engine_generate_loop(
    ggml_engine_t * engine,
    ggml_engine_sampling sampling,
    ggml_engine_token_callback callback,
    void * user_data
) {
    const int n_ctx = llama_n_ctx(engine->ctx);

    auto sparams = llama_sampler_chain_default_params();
    struct llama_sampler * smpl = llama_sampler_chain_init(sparams);

    if (sampling.repeat_penalty != 1.0f || sampling.frequency_penalty != 0.0f || sampling.presence_penalty != 0.0f) {
        llama_sampler_chain_add(smpl,
            llama_sampler_init_penalties(
                sampling.repeat_last_n,
                sampling.repeat_penalty,
                sampling.frequency_penalty,
                sampling.presence_penalty));
    }

    if (sampling.top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sampling.top_k));
    }
    if (sampling.top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sampling.top_p, 1));
    }
    if (sampling.min_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(sampling.min_p, 1));
    }
    if (sampling.temperature > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(sampling.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(sampling.seed));
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    }

    // Cache stop sequence lengths once before the loop (avoids strlen per token)
    size_t stop_lens[8] = {};
    int n_stops = sampling.stop_sequence_count < 8 ? sampling.stop_sequence_count : 8;
    for (int s = 0; s < n_stops; s++) {
        stop_lens[s] = sampling.stop_sequences[s] ? strlen(sampling.stop_sequences[s]) : 0;
    }

    int64_t t_gen_start = llama_time_us();
    int n_generated = 0;
    int max_tokens = sampling.n_predict > 0 ? sampling.n_predict : n_ctx - engine->n_past;
    engine->response.reserve(max_tokens * 4);

    // Use cached single-token batch — no malloc per generate() call
    ggml_engine_ensure_batch(engine);
    struct llama_batch & batch = engine->batch;

    char piece_buf[256];

    while (n_generated < max_tokens) {
        if (engine->cancelled.load()) {
            llama_sampler_free(smpl);
            return GGML_ENGINE_ERROR_CANCELLED;
        }

        llama_token new_token = llama_sampler_sample(smpl, engine->ctx, -1);

        if (llama_vocab_is_eog(engine->vocab, new_token)) {
            break;
        }

        // Append directly — no temporary std::string allocation per token
        int n = llama_token_to_piece(engine->vocab, new_token, piece_buf, sizeof(piece_buf), 0, true);
        if (n < 0) n = 0;
        engine->response.append(piece_buf, (size_t)n);
        n_generated++;

        // Windowed stop-sequence check using pre-cached lengths
        bool should_stop = false;
        for (int s = 0; s < n_stops; s++) {
            if (!sampling.stop_sequences[s] || stop_lens[s] == 0) continue;
            if (engine->response.size() < stop_lens[s]) continue;

            size_t window = stop_lens[s] + (size_t)n;
            size_t from = engine->response.size() > window ? engine->response.size() - window : 0;
            size_t pos = engine->response.find(sampling.stop_sequences[s], from);
            if (pos != std::string::npos) {
                engine->response.erase(pos);
                should_stop = true;
                break;
            }
        }

        if (should_stop) break;

        if (callback) {
            // Pass the piece directly from the stack buffer
            piece_buf[n] = '\0';
            if (!callback(piece_buf, user_data)) {
                break;
            }
        }

        // StreamingLLM eviction: if the policy is active and we're at budget,
        // evict old non-sink tokens before decoding the next one.
        if (engine->kv_policy.n_window > 0) {
            ggml_engine_kv_evict_internal(engine);
        }

        common_batch_clear(batch);
        common_batch_add(batch, new_token, engine->n_past, {0}, true);

        if (llama_decode(engine->ctx, batch) != 0) {
            llama_sampler_free(smpl);
            return GGML_ENGINE_ERROR_DECODE;
        }
        engine->n_past++;
    }

    int64_t t_gen_end = llama_time_us();
    engine->perf.generation_ms = (t_gen_end - t_gen_start) / 1000.0;
    engine->perf.generated_tokens = n_generated;

    if (engine->perf.prompt_eval_ms > 0) {
        engine->perf.prompt_tokens_per_sec =
            engine->perf.prompt_tokens / (engine->perf.prompt_eval_ms / 1000.0);
    }
    if (engine->perf.generation_ms > 0) {
        engine->perf.generation_tokens_per_sec =
            engine->perf.generated_tokens / (engine->perf.generation_ms / 1000.0);
    }

    llama_sampler_free(smpl);

    return GGML_ENGINE_OK;
}
