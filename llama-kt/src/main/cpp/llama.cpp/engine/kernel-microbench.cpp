// Kernel micro-benchmark — measures one ggml_mul_mat in isolation.
//
// Designed to be `adb push`'d to a device and run from `/data/local/tmp/`,
// giving a 30-second iteration cycle when you're chasing a kernel-level
// throughput regression (e.g. K2 i8mm n=1, K3 repack gemv) without the
// noise of a full model decode.
//
// Default shape: 2048 × 2048 Q4_0 × F32 with n=1 — matches the gemma/llama
// 1B Q proj exactly. Override with --in / --out / --type / --threads / --iters.

#include "ggml.h"
#include "ggml-cpu.h"
#include "ggml-backend.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

static double now_ms() {
    using clock = std::chrono::high_resolution_clock;
    return std::chrono::duration<double, std::milli>(clock::now().time_since_epoch()).count();
}

static ggml_type parse_type(const char * s) {
    if (!strcmp(s, "q4_0")) return GGML_TYPE_Q4_0;
    if (!strcmp(s, "q4_1")) return GGML_TYPE_Q4_1;
    if (!strcmp(s, "q4_K")) return GGML_TYPE_Q4_K;
    if (!strcmp(s, "q5_0")) return GGML_TYPE_Q5_0;
    if (!strcmp(s, "q5_K")) return GGML_TYPE_Q5_K;
    if (!strcmp(s, "q6_K")) return GGML_TYPE_Q6_K;
    if (!strcmp(s, "q8_0")) return GGML_TYPE_Q8_0;
    if (!strcmp(s, "f16"))  return GGML_TYPE_F16;
    if (!strcmp(s, "f32"))  return GGML_TYPE_F32;
    fprintf(stderr, "unknown type '%s' — falling back to q4_0\n", s);
    return GGML_TYPE_Q4_0;
}

int main(int argc, char ** argv) {
    // Defaults: gemma 3 1B / llama 3.2 1B Q proj shape — 2048 in -> 2048 out
    int n_in     = 2048;
    int n_out    = 2048;
    int n_tokens = 1;       // token-gen scenario; bump to 64+ for prefill bench
    int n_threads = 2;
    int iters    = 200;
    const char * type_str = "q4_0";

    for (int i = 1; i < argc; i++) {
        if (i + 1 >= argc) break;
        if      (!strcmp(argv[i], "--in"))      n_in      = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--out"))     n_out     = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--tokens"))  n_tokens  = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--threads")) n_threads = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--iters"))   iters     = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--type"))    type_str  = argv[++i];
    }

    ggml_type wtype = parse_type(type_str);

    // Quantized types require dim divisible by their block size. Q4_0/Q5_0/Q8_0
    // use 32-element blocks; the K-quants use 256-element super-blocks. Bail
    // with a clear error rather than producing nonsense.
    const int blk = ggml_blck_size(wtype);
    if (n_in % blk != 0) {
        fprintf(stderr, "n_in (%d) not divisible by block size %d for type %s\n",
                n_in, blk, type_str);
        return 1;
    }

    fprintf(stderr, "microbench: %d -> %d, type=%s (blk=%d), n_tokens=%d, threads=%d, iters=%d\n",
            n_in, n_out, type_str, blk, n_tokens, n_threads, iters);

    ggml_backend_load_all();

    // One context big enough for both tensors + the graph. The weight tensor
    // dominates: ~2 MB at Q4_0 / 2048×2048, plus activations + work buffer.
    // 256 MB is comfortable and avoids realloc dance during build_forward.
    struct ggml_init_params iparams = {};
    iparams.mem_size   = (size_t)256 * 1024 * 1024;
    iparams.mem_buffer = nullptr;
    iparams.no_alloc   = false;
    struct ggml_context * ctx = ggml_init(iparams);
    if (!ctx) { fprintf(stderr, "ggml_init failed\n"); return 1; }

    ggml_tensor * w = ggml_new_tensor_2d(ctx, wtype,         n_in, n_out);
    ggml_tensor * x = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, n_in, n_tokens);

    // Random fill, then quantize the weight in-place if needed. Activations
    // stay F32. Quantize from a scratch F32 buffer because ggml_quantize_chunk
    // wants a separate source.
    std::vector<float> wbuf((size_t)n_in * n_out);
    std::vector<float> xbuf((size_t)n_in * n_tokens);
    srand(42);
    for (auto & v : wbuf) v = ((float)(rand() % 2000) / 1000.0f) - 1.0f;
    for (auto & v : xbuf) v = ((float)(rand() % 2000) / 1000.0f) - 1.0f;

    if (wtype == GGML_TYPE_F32) {
        memcpy(w->data, wbuf.data(), wbuf.size() * sizeof(float));
    } else if (wtype == GGML_TYPE_F16) {
        // F32 -> F16 via the type traits (avoids a direct dep on a row helper)
        const auto * tt = ggml_get_type_traits(wtype);
        if (tt && tt->from_float_ref) {
            tt->from_float_ref(wbuf.data(), w->data, wbuf.size());
        } else {
            fprintf(stderr, "no from_float for f16 — bailing\n");
            return 1;
        }
    } else {
        ggml_quantize_init(wtype);
        ggml_quantize_chunk(wtype, wbuf.data(), w->data, /*start=*/0, n_out, n_in, /*imatrix=*/nullptr);
    }
    memcpy(x->data, xbuf.data(), xbuf.size() * sizeof(float));

    ggml_tensor * y = ggml_mul_mat(ctx, w, x);

    ggml_cgraph * gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, y);

    // Persistent threadpool so we don't measure thread-spawn overhead. poll=0
    // mirrors the mobile-friendly config we use in gguf_lib.
    ggml_threadpool_params tparams;
    ggml_threadpool_params_init(&tparams, n_threads);
    tparams.poll = 0;
    tparams.prio = GGML_SCHED_PRIO_NORMAL;
    ggml_threadpool_t tp = ggml_threadpool_new(&tparams);

    ggml_cplan cplan = ggml_graph_plan(gf, n_threads, tp);
    std::vector<uint8_t> work(cplan.work_size);
    cplan.work_data = work.data();

    // Warm-up — first few iterations include page-fault / cache-miss noise
    for (int i = 0; i < 5; i++) {
        ggml_graph_compute(gf, &cplan);
    }

    // Measure
    std::vector<double> times_ms;
    times_ms.reserve(iters);
    for (int i = 0; i < iters; i++) {
        const double t0 = now_ms();
        ggml_graph_compute(gf, &cplan);
        times_ms.push_back(now_ms() - t0);
    }
    std::sort(times_ms.begin(), times_ms.end());

    const double min_ms    = times_ms.front();
    const double median_ms = times_ms[times_ms.size() / 2];
    const double p99_ms    = times_ms[(times_ms.size() * 99) / 100];

    const size_t w_bytes   = ggml_nbytes(w);
    const size_t x_bytes   = ggml_nbytes(x);
    const size_t y_bytes   = ggml_nbytes(y);
    const size_t total_bytes = w_bytes + x_bytes + y_bytes;
    const double w_mb      = w_bytes / (1024.0 * 1024.0);
    // Effective bandwidth: bytes read per call ≈ weight bytes (dominates for
    // small n_tokens). For prefill bench scale this by checking total.
    const double gbps_w    = (double)w_bytes / (median_ms / 1000.0) / 1e9;
    const double gbps_all  = (double)total_bytes / (median_ms / 1000.0) / 1e9;

    fprintf(stderr, "\n");
    fprintf(stdout, "shape=%dx%dx%d type=%s threads=%d iters=%d\n",
            n_in, n_out, n_tokens, type_str, n_threads, iters);
    fprintf(stdout, "ms_per_op  min=%.4f  median=%.4f  p99=%.4f\n", min_ms, median_ms, p99_ms);
    fprintf(stdout, "weights    %.2f MB    eff_bw_w=%.2f GB/s    eff_bw_all=%.2f GB/s\n",
            w_mb, gbps_w, gbps_all);

    ggml_threadpool_free(tp);
    ggml_quantize_free();
    ggml_free(ctx);
    return 0;
}
