#include "ggml-engine-internal.h"
#include "vlm/mtmd.h"
#include "vlm/mtmd-helper.h"
#include "vlm/clip.h"
#include "tn-log.h"

#include <sstream>
#include <cstring>

struct ggml_engine_vlm {
    mtmd_context       * mtmd_ctx = nullptr;
    ggml_engine_t      * engine   = nullptr;
    ggml_engine_vlm_params params;
};

ggml_engine_vlm_params ggml_engine_vlm_default_params(void) {
    ggml_engine_vlm_params p{};
    p.n_threads        = 0;
    p.image_min_tokens = -1;
    p.image_max_tokens = -1;
    return p;
}

ggml_engine_vlm_t * ggml_engine_vlm_load(
    ggml_engine_t * engine, const char * mmproj_path,
    ggml_engine_vlm_params params)
{
    if (!engine || !engine->model || !mmproj_path) return nullptr;

    auto mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu = false;
    mtmd_params.n_threads = params.n_threads > 0 ? params.n_threads : engine->params.n_threads_batch;
    mtmd_params.print_timings = false;
    mtmd_params.warmup = true;

    if (engine->params.flash_attn) {
        mtmd_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }

    if (params.image_min_tokens >= 0) {
        mtmd_params.image_min_tokens = params.image_min_tokens;
    }
    if (params.image_max_tokens >= 0) {
        mtmd_params.image_max_tokens = params.image_max_tokens;
    }

    mtmd_context * mtmd_ctx = mtmd_init_from_file(mmproj_path, engine->model, mtmd_params);
    if (!mtmd_ctx) {
        return nullptr;
    }

    auto * vlm = new ggml_engine_vlm();
    vlm->mtmd_ctx = mtmd_ctx;
    vlm->engine   = engine;
    vlm->params   = params;

    return vlm;
}

ggml_engine_vlm_t * ggml_engine_vlm_load_from_fd(
    ggml_engine_t * engine, int fd,
    ggml_engine_vlm_params params)
{
    if (fd < 0) return nullptr;
    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    return ggml_engine_vlm_load(engine, fd_path, params);
}

void ggml_engine_vlm_free(ggml_engine_vlm_t * vlm) {
    if (!vlm) return;
    if (vlm->mtmd_ctx) {
        mtmd_free(vlm->mtmd_ctx);
    }
    delete vlm;
}

bool ggml_engine_vlm_is_loaded(const ggml_engine_vlm_t * vlm) {
    return vlm && vlm->mtmd_ctx;
}

ggml_engine_status ggml_engine_vlm_generate(
    ggml_engine_t * engine, ggml_engine_vlm_t * vlm,
    const char * prompt,
    const ggml_engine_image * images, int32_t n_images,
    ggml_engine_sampling sampling,
    ggml_engine_token_callback callback, void * user_data)
{
    if (!engine || !engine->model || !engine->ctx) {
        return GGML_ENGINE_ERROR_NO_MODEL;
    }
    if (!vlm || !vlm->mtmd_ctx) {
        return GGML_ENGINE_ERROR_VLM_NO_PROJ;
    }

    if (n_images > 0 && !images) {
        return GGML_ENGINE_ERROR_VLM_ENCODE;
    }

    engine->cancelled.store(false);
    engine->response.clear();
    memset(&engine->perf, 0, sizeof(engine->perf));

    std::vector<mtmd_bitmap *> bitmaps;
    for (int32_t i = 0; i < n_images; i++) {
        mtmd_bitmap * bmp = nullptr;
        if (images[i].width == 0 || images[i].height == 0) {
            bmp = mtmd_helper_bitmap_init_from_buf(vlm->mtmd_ctx, images[i].data, images[i].size);
        } else {
            bmp = mtmd_bitmap_init(images[i].width, images[i].height, images[i].data);
        }
        if (!bmp) {
            for (auto * b : bitmaps) mtmd_bitmap_free(b);
            return GGML_ENGINE_ERROR_VLM_ENCODE;
        }
        bitmaps.push_back(bmp);
    }

    std::vector<const mtmd_bitmap *> bitmap_ptrs(bitmaps.begin(), bitmaps.end());

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    mtmd_input_text input_text;
    input_text.text = prompt;
    input_text.add_special = true;
    input_text.parse_special = true;

    int32_t tok_result = mtmd_tokenize(vlm->mtmd_ctx, chunks,
        &input_text, bitmap_ptrs.data(), bitmap_ptrs.size());

    for (auto * b : bitmaps) mtmd_bitmap_free(b);

    if (tok_result != 0) {
        mtmd_input_chunks_free(chunks);
        return GGML_ENGINE_ERROR_TOKENIZE;
    }

    llama_memory_t mem = llama_get_memory(engine->ctx);
    if (mem) {
        llama_memory_clear(mem, true);
    }
    engine->n_past = 0;

    int64_t t_prompt_start = llama_time_us();

    // Walk chunks manually so we can separate vision-encode time from LLM
    // prompt-eval time on image embeddings. The BALANCED log showed this loop
    // spending almost all of its wall in the decode half, not in mtmd_encode.
    int64_t t_encode_us = 0;
    int64_t t_decode_us = 0;
    int32_t n_image_tokens = 0;
    llama_pos n_past = 0;

    const size_t n_chunks = mtmd_input_chunks_size(chunks);
    int32_t eval_result = 0;

    for (size_t i = 0; i < n_chunks && eval_result == 0; i++) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks, i);
        const bool logits_last = (i == n_chunks - 1);
        const enum mtmd_input_chunk_type ctype = mtmd_input_chunk_get_type(chunk);

        if (ctype == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            const int64_t t0 = llama_time_us();
            eval_result = mtmd_helper_eval_chunk_single(
                vlm->mtmd_ctx, engine->ctx, chunk,
                n_past, 0, engine->params.n_batch, logits_last, &n_past);
            t_decode_us += llama_time_us() - t0;
        } else {
            // Vision / audio encoder forward pass
            const int64_t t_enc0 = llama_time_us();
            eval_result = mtmd_encode_chunk(vlm->mtmd_ctx, chunk);
            t_encode_us += llama_time_us() - t_enc0;

            if (eval_result != 0) break;

            // LLM consumes the embeddings as an embd-batch
            float * embd = mtmd_get_output_embd(vlm->mtmd_ctx);
            const int32_t n_tok = (int32_t)mtmd_input_chunk_get_n_tokens(chunk);
            n_image_tokens += n_tok;

            const int64_t t_dec0 = llama_time_us();
            eval_result = mtmd_helper_decode_image_chunk(
                vlm->mtmd_ctx, engine->ctx, chunk, embd,
                n_past, 0, engine->params.n_batch, &n_past);
            t_decode_us += llama_time_us() - t_dec0;
        }
    }

    mtmd_input_chunks_free(chunks);

    if (eval_result != 0) {
        return GGML_ENGINE_ERROR_VLM_ENCODE;
    }

    engine->n_past = n_past;

    const int64_t t_prompt_end = llama_time_us();
    engine->perf.prompt_eval_ms   = (t_prompt_end - t_prompt_start) / 1000.0;
    engine->perf.prompt_tokens    = engine->n_past;
    engine->perf.vlm_encode_ms    = t_encode_us / 1000.0;
    engine->perf.vlm_decode_ms    = t_decode_us / 1000.0;
    engine->perf.vlm_image_tokens = n_image_tokens;
    // Anything left over in prompt_eval_ms is tokenize + preprocessing
    double accounted = engine->perf.vlm_encode_ms + engine->perf.vlm_decode_ms;
    double remainder = engine->perf.prompt_eval_ms - accounted;
    engine->perf.vlm_tokenize_ms  = remainder > 0.0 ? remainder : 0.0;

    TN_LOG_INF("VLM stage breakdown: tokenize+preproc=%.1fms  encode=%.1fms  decode=%.1fms  image_tokens=%d  total_prompt_tokens=%d",
               engine->perf.vlm_tokenize_ms,
               engine->perf.vlm_encode_ms,
               engine->perf.vlm_decode_ms,
               engine->perf.vlm_image_tokens,
               engine->perf.prompt_tokens);

    return ggml_engine_generate_loop(engine, sampling, callback, user_data);
}

int32_t ggml_engine_vlm_encode_image(
    ggml_engine_vlm_t * vlm, const ggml_engine_image * image)
{
    if (!vlm || !vlm->mtmd_ctx || !image) return -1;

    mtmd_bitmap * bmp = nullptr;
    if (image->width == 0 || image->height == 0) {
        bmp = mtmd_helper_bitmap_init_from_buf(vlm->mtmd_ctx, image->data, image->size);
    } else {
        bmp = mtmd_bitmap_init(image->width, image->height, image->data);
    }
    if (!bmp) return -1;

    // tokenize with marker prompt to estimate image token count
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    mtmd_input_text input_text;
    const char * marker = mtmd_default_marker();
    input_text.text = marker;
    input_text.add_special = false;
    input_text.parse_special = true;

    const mtmd_bitmap * bmp_ptr = bmp;
    int32_t tok_result = mtmd_tokenize(vlm->mtmd_ctx, chunks,
        &input_text, &bmp_ptr, 1);

    mtmd_bitmap_free(bmp);

    if (tok_result != 0) {
        mtmd_input_chunks_free(chunks);
        return -1;
    }

    int32_t n_image_tokens = 0;
    size_t n_chunks = mtmd_input_chunks_size(chunks);
    for (size_t i = 0; i < n_chunks; i++) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks, i);
        if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_IMAGE) {
            n_image_tokens += (int32_t)mtmd_input_chunk_get_n_tokens(chunk);
        }
    }

    mtmd_input_chunks_free(chunks);
    return n_image_tokens;
}

char * ggml_engine_vlm_info_json(const ggml_engine_vlm_t * vlm) {
    if (!vlm || !vlm->mtmd_ctx) return strdup_alloc("{}");

    std::ostringstream json;
    json << "{\n";
    json << "  \"supports_vision\": " << (mtmd_support_vision(vlm->mtmd_ctx) ? "true" : "false") << ",\n";
    json << "  \"supports_audio\": " << (mtmd_support_audio(vlm->mtmd_ctx) ? "true" : "false") << ",\n";
    json << "  \"uses_mrope\": " << (mtmd_decode_use_mrope(vlm->mtmd_ctx) ? "true" : "false") << ",\n";
    json << "  \"uses_non_causal\": " << (mtmd_decode_use_non_causal(vlm->mtmd_ctx) ? "true" : "false") << ",\n";
    json << "  \"audio_bitrate\": " << mtmd_get_audio_bitrate(vlm->mtmd_ctx) << ",\n";
    json << "  \"default_marker\": \"" << mtmd_default_marker() << "\"\n";
    json << "}";

    return strdup_alloc(json.str());
}

const char * ggml_engine_vlm_default_marker(void) {
    return mtmd_default_marker();
}

bool ggml_engine_vlm_supports_vision(const ggml_engine_vlm_t * vlm) {
    if (!vlm || !vlm->mtmd_ctx) return false;
    return mtmd_support_vision(vlm->mtmd_ctx);
}

bool ggml_engine_vlm_supports_audio(const ggml_engine_vlm_t * vlm) {
    if (!vlm || !vlm->mtmd_ctx) return false;
    return mtmd_support_audio(vlm->mtmd_ctx);
}
