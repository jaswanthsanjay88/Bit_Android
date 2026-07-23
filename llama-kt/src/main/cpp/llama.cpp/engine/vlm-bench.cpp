// Android CPU-only VLM timing benchmark for Tool-Neuron engine.
// Usage: vlm-bench <model.gguf> <mmproj.gguf> <image> [max_tokens]

#include "ggml-engine.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

static bool read_file(const char * path, std::vector<unsigned char> & out) {
    FILE * f = std::fopen(path, "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    out.resize((size_t)sz);
    size_t n = std::fread(out.data(), 1, (size_t)sz, f);
    std::fclose(f);
    return n == (size_t)sz;
}

static bool on_token(const char * tok, void * /*ud*/) {
    std::fputs(tok, stdout);
    std::fflush(stdout);
    return true;
}

int main(int argc, char ** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s <model.gguf> <mmproj.gguf> <image> [max_tokens]\n", argv[0]);
        return 1;
    }

    const char * model_path  = argv[1];
    const char * mmproj_path = argv[2];
    const char * image_path  = argv[3];
    int32_t      max_tokens  = argc >= 5 ? std::atoi(argv[4]) : 32;

    ggml_engine_params ep = ggml_engine_default_params();
    ep.n_ctx       = 4096;
    ep.flash_attn  = true;
    ep.thread_mode = 1; // BALANCED

    ggml_engine_t * eng = ggml_engine_create(ep);
    if (!eng) { std::fprintf(stderr, "engine_create failed\n"); return 1; }

    if (ggml_engine_load_model(eng, model_path) != GGML_ENGINE_OK) {
        std::fprintf(stderr, "load_model failed: %s\n", model_path);
        return 1;
    }

    ggml_engine_vlm_params vp = ggml_engine_vlm_default_params();
    ggml_engine_vlm_t * vlm = ggml_engine_vlm_load(eng, mmproj_path, vp);
    if (!vlm) { std::fprintf(stderr, "vlm_load failed: %s\n", mmproj_path); return 1; }

    std::vector<unsigned char> img_bytes;
    if (!read_file(image_path, img_bytes)) {
        std::fprintf(stderr, "cannot read image: %s\n", image_path);
        return 1;
    }
    std::printf("[bench] image: %s (%.2f MiB)\n", image_path, img_bytes.size() / 1048576.0);

    ggml_engine_image img{};
    img.data   = img_bytes.data();
    img.size   = img_bytes.size();
    img.width  = 0;
    img.height = 0;

    ggml_engine_sampling sp = ggml_engine_default_sampling();
    sp.n_predict = max_tokens;
    sp.temperature = 0.0f; // greedy, deterministic

    const std::string prompt =
        "<|im_start|>user\n<__media__>Describe this image briefly.<|im_end|>\n<|im_start|>assistant\n";

    std::printf("[bench] generating...\n");
    ggml_engine_status st = ggml_engine_vlm_generate(
        eng, vlm, prompt.c_str(), &img, 1, sp, on_token, nullptr);
    std::printf("\n[bench] status=%d\n", (int)st);

    ggml_engine_perf p = ggml_engine_get_perf(eng);
    std::printf("\n==== TIMING ====\n");
    std::printf("prompt_tokens    : %d\n", p.prompt_tokens);
    std::printf("image_tokens     : %d\n", p.vlm_image_tokens);
    std::printf("prompt_eval_ms   : %.1f  (%.1f tok/s)\n", p.prompt_eval_ms, p.prompt_tokens_per_sec);
    std::printf("  tokenize+preproc: %.1f ms\n", p.vlm_tokenize_ms);
    std::printf("  vision encode   : %.1f ms\n", p.vlm_encode_ms);
    std::printf("  llm decode      : %.1f ms  (%.1f img-tok/s)\n",
                p.vlm_decode_ms,
                p.vlm_image_tokens > 0 && p.vlm_decode_ms > 0.0
                    ? p.vlm_image_tokens * 1000.0 / p.vlm_decode_ms : 0.0);
    std::printf("generation_ms    : %.1f  (%d tok, %.1f tok/s)\n",
                p.generation_ms, p.generated_tokens, p.generation_tokens_per_sec);

    ggml_engine_vlm_free(vlm);
    ggml_engine_free(eng);
    return 0;
}
