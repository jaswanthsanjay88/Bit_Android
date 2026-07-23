#include "thread-engine.h"
#include "tn-log.h"

#include <cstdio>
#include <algorithm>
#include <cstring>
#include <functional>

#ifdef __ANDROID__
#include <sys/sysinfo.h>
#include <unistd.h>
#endif

#if defined(__linux__) || defined(__ANDROID__)
#include <dirent.h>
#endif

// Read integer from sysfs path, returns -1 on failure
static int read_sysfs_int(const char * path) {
    FILE * f = fopen(path, "r");
    if (!f) return -1;
    int val = -1;
    if (fscanf(f, "%d", &val) != 1) val = -1;
    fclose(f);
    return val;
}

// Read max frequency for a CPU core in KHz
static int core_max_freq_khz(int cpu_id) {
    char path[128];
    snprintf(path, sizeof(path),
             "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu_id);
    return read_sysfs_int(path);
}

// Check if a CPU core is online
static bool core_is_online(int cpu_id) {
    if (cpu_id == 0) return true; // cpu0 is always online
    char path[128];
    snprintf(path, sizeof(path),
             "/sys/devices/system/cpu/cpu%d/online", cpu_id);
    return read_sysfs_int(path) == 1;
}

// Largest-gap cluster classification. Input must be sorted DESCENDING.
// Finds the largest %-drop between adjacent cores and splits there:
// everything above the drop = perf, below = eff. If the largest drop is
// below MIN_CLUSTER_GAP, the device is treated as a single uniform tier
// (no big.LITTLE split — all cores are perf).
//
// Replaces the old 5%-of-max threshold which only caught the very top
// frequency tier and misclassified sub-perf cores on devices that have a
// prime+perf split inside the big cluster. Exynos 1580 (Samsung A56):
// 1× A720 @ 2.91 GHz + 3× A720 @ 2.6 GHz + 4× A520 @ 1.95 GHz — old
// classifier returned n_perf=1 (only the prime), starving inference to
// a single decode thread (~1 tok/s on 4B q3 vs the ~5 tok/s the perf
// cluster can sustain). Largest gap is 2.6→1.95 (25%), well above the
// 11% intra-perf-cluster gap, so the new classifier splits correctly at 4.
//
// Also handles Tensor G3 (Pixel 8: prime+perf+eff three-tier) and
// SD 8 Gen 3 (X4 prime + A720 sub-perf + A520 eff) — both have an
// intra-perf-cluster drop smaller than the perf→eff drop.
static constexpr double MIN_CLUSTER_GAP = 0.15;

static int classify_perf_split(const int * freqs_desc, int n) {
    if (n <= 1) return n;
    if (freqs_desc[0] <= 0) return n;
    int best_split = -1;
    double best_drop = 0.0;
    for (int i = 1; i < n; i++) {
        if (freqs_desc[i] <= 0 || freqs_desc[i - 1] <= 0) continue;
        double drop = (double)(freqs_desc[i - 1] - freqs_desc[i]) /
                      (double)freqs_desc[i - 1];
        if (drop > best_drop) {
            best_drop = drop;
            best_split = i;
        }
    }
    if (best_split < 0 || best_drop < MIN_CLUSTER_GAP) return n;
    return best_split;
}

tn_device_info tn_detect_device(void) {
    tn_device_info info = {};

#if defined(__linux__) || defined(__ANDROID__)
    DIR * dir = opendir("/sys/devices/system/cpu");
    if (!dir) {
        info.n_cores_total = 1;
        return info;
    }

    int freqs[64] = {};
    int n_cores = 0;

    struct dirent * entry;
    while ((entry = readdir(dir)) != nullptr) {
        int cpu_id = -1;
        if (sscanf(entry->d_name, "cpu%d", &cpu_id) == 1 && cpu_id >= 0 && cpu_id < 64) {
            if (!core_is_online(cpu_id)) continue;
            freqs[n_cores] = core_max_freq_khz(cpu_id);
            n_cores++;
        }
    }
    closedir(dir);

    if (n_cores == 0) {
        info.n_cores_total = 1;
        return info;
    }

    info.n_cores_total = n_cores;

    int max_freq = 0, min_freq = 0x7FFFFFFF;
    for (int i = 0; i < n_cores; i++) {
        if (freqs[i] > 0) {
            if (freqs[i] > max_freq) max_freq = freqs[i];
            if (freqs[i] < min_freq) min_freq = freqs[i];
        }
    }
    info.max_freq_khz = max_freq;
    info.min_freq_khz = min_freq;

    int sorted_freqs[64];
    std::memcpy(sorted_freqs, freqs, sizeof(int) * n_cores);
    std::sort(sorted_freqs, sorted_freqs + n_cores, std::greater<int>());
    int n_perf = classify_perf_split(sorted_freqs, n_cores);
    int n_eff  = n_cores - n_perf;

    info.n_perf_cores = n_perf;
    info.n_efficiency_cores = n_eff;

    TN_LOG_INF("device: %d cores (%d perf, %d eff), freq %d-%d MHz",
               n_cores, n_perf, n_eff, min_freq / 1000, max_freq / 1000);

#else
    // Fallback for non-Linux
    int n = 4;
#ifdef _SC_NPROCESSORS_ONLN
    n = (int)sysconf(_SC_NPROCESSORS_ONLN);
    if (n < 1) n = 4;
#endif
    info.n_cores_total = n;
    info.n_perf_cores = n;
    info.n_efficiency_cores = 0;
#endif

    return info;
}

// Build a ggml-style cpumask (bool per CPU) from a list of core IDs. Out-of-
// range IDs are silently dropped — there is no diagnostic value in failing
// because the calling code already logs the per-mode summary line.
static void fill_cpumask(bool * mask, const int32_t * ids, int n_ids) {
    std::memset(mask, 0, sizeof(bool) * TN_MAX_CPUS);
    for (int i = 0; i < n_ids; i++) {
        int c = (int)ids[i];
        if (c >= 0 && c < TN_MAX_CPUS) mask[c] = true;
    }
}

tn_thread_config tn_thread_config_for_mode(tn_thread_mode mode) {
    tn_thread_config cfg = {};
    tn_device_info dev = tn_detect_device();

#if defined(__linux__) || defined(__ANDROID__)
    // Build sorted core lists by frequency
    struct core_info { int id; int freq; };
    core_info cores[64] = {};
    int n = 0;

    DIR * dir = opendir("/sys/devices/system/cpu");
    if (dir) {
        struct dirent * entry;
        while ((entry = readdir(dir)) != nullptr && n < 64) {
            int cpu_id = -1;
            if (sscanf(entry->d_name, "cpu%d", &cpu_id) == 1 && cpu_id >= 0) {
                if (!core_is_online(cpu_id)) continue;
                cores[n].id = cpu_id;
                cores[n].freq = core_max_freq_khz(cpu_id);
                n++;
            }
        }
        closedir(dir);
    }

    // Sort by frequency descending (fastest first)
    std::sort(cores, cores + n, [](const core_info & a, const core_info & b) {
        return a.freq > b.freq;
    });

    // Largest-gap split (see classify_perf_split). cores[] is sorted desc.
    int freqs_only[64] = {};
    for (int i = 0; i < n; i++) freqs_only[i] = cores[i].freq;
    const int n_perf_target = classify_perf_split(freqs_only, n);
    int n_perf = 0, n_eff = 0;
    for (int i = 0; i < n; i++) {
        if (i < n_perf_target && n_perf < 16) {
            cfg.perf_core_ids[n_perf++] = cores[i].id;
        } else if (n_eff < 16) {
            cfg.efficiency_core_ids[n_eff++] = cores[i].id;
        }
    }
    cfg.n_perf_core_ids = n_perf;
    cfg.n_efficiency_core_ids = n_eff;
#else
    cfg.n_perf_core_ids = dev.n_perf_cores;
    cfg.n_efficiency_core_ids = 0;
#endif

    // np = TRUE perf cluster count (post cluster-classification). On modern
    // big.LITTLE SoCs this is typically 4 (e.g. Snapdragon 7s Gen 3:
    // 4× A720 perf + 4× A520 eff). ne = eff cluster count.
    int np      = cfg.n_perf_core_ids > 0 ? cfg.n_perf_core_ids : dev.n_cores_total;
    int ne      = cfg.n_efficiency_core_ids;
    int n_total = dev.n_cores_total > 0 ? dev.n_cores_total : 4;
    if (np <= 0) np = 4;

    // Default knobs shared across modes. Polling=0 because we always block on
    // user input between decodes — busy-spinning burns battery for nothing.
    cfg.poll = 0;

    switch (mode) {
        case TN_THREAD_POWER_SAVING:
            // Pin to the efficiency cluster (A520 on 7s Gen 3 etc). Single
            // decode thread, two batch threads, small n_batch. The defining
            // property here is *not* speed — it's predictable low power draw
            // even under sustained generation. Without pinning the Android
            // scheduler will happily run "low power" workloads on the prime
            // core whenever it's not contended, leaking power vs PERFORMANCE.
            cfg.n_threads_generation = 1;
            cfg.n_threads_batch      = ne > 0 ? std::min(2, ne) : std::min(2, np);
            cfg.n_batch              = 128;
            cfg.pin_to_perf_cores    = false;
            cfg.pin_to_eff_cores     = (ne > 0);
            cfg.priority             = TN_PRIO_LOW;
            if (ne > 0) {
                fill_cpumask(cfg.cpumask_generation,
                             cfg.efficiency_core_ids, ne);
                fill_cpumask(cfg.cpumask_batch,
                             cfg.efficiency_core_ids, ne);
            }
            break;

        case TN_THREAD_BALANCED:
            // Pin to the perf cluster. Two decode threads — empirically the
            // sweet spot on shared-L3 perf clusters (gen=4 thrashes L3, gen=1
            // leaves bandwidth on the table on 7s Gen 3 / 8 Gen 1+).
            //
            // With explicit pinning we get the same throughput as the lucky-
            // placement case but eliminate the variance that made POWER_SAVING
            // sometimes match PERFORMANCE on the same prompt — the kernel was
            // randomly placing decode threads on the eff cluster.
            //
            // n_batch=512: prompt eval is dominated by graph build / scheduler
            // overhead at small batches; doubling n_batch from 256 halves
            // those calls and improves prompt-eval throughput on small models.
            cfg.n_threads_generation = std::min(2, np);
            cfg.n_threads_batch      = np;
            cfg.n_batch              = 512;
            cfg.pin_to_perf_cores    = true;
            cfg.pin_to_eff_cores     = false;
            cfg.priority             = TN_PRIO_NORMAL;
            fill_cpumask(cfg.cpumask_generation, cfg.perf_core_ids, np);
            fill_cpumask(cfg.cpumask_batch,      cfg.perf_core_ids, np);
            break;

        case TN_THREAD_PERFORMANCE:
            // Same pinning as BALANCED but pushes decode to 3 threads on the
            // perf cluster. The third A720 contributes meaningfully on small
            // models (<1B) where bandwidth isn't yet saturated. On larger
            // models the extra thread plateaus but doesn't hurt because we're
            // still inside the perf cluster's shared L3. Priority HIGH so the
            // OS won't deprioritize us when the screen is on but the foreground
            // app isn't us (we're a foreground service).
            //
            // n_threads_batch = n_total: prompt eval is compute-bound, all
            // cores including eff contribute. n_batch=1024 halves graph-build
            // overhead on long prompts.
            cfg.n_threads_generation = std::min(3, np);
            cfg.n_threads_batch      = n_total;
            cfg.n_batch              = 1024;
            cfg.pin_to_perf_cores    = true;
            cfg.pin_to_eff_cores     = false;
            cfg.priority             = TN_PRIO_HIGH;
            fill_cpumask(cfg.cpumask_generation, cfg.perf_core_ids, np);
            // Batch mask covers ALL online cores. We build it from the union
            // of perf + eff core IDs rather than indexing 0..n_total because
            // some devices skip CPU IDs (offline cores, hotplug holes).
            {
                std::memset(cfg.cpumask_batch, 0, sizeof(cfg.cpumask_batch));
                for (int i = 0; i < np; i++) {
                    int c = (int)cfg.perf_core_ids[i];
                    if (c >= 0 && c < TN_MAX_CPUS) cfg.cpumask_batch[c] = true;
                }
                for (int i = 0; i < ne; i++) {
                    int c = (int)cfg.efficiency_core_ids[i];
                    if (c >= 0 && c < TN_MAX_CPUS) cfg.cpumask_batch[c] = true;
                }
            }
            break;
    }

    TN_LOG_INF("thread mode %d: gen=%d batch=%d n_batch=%d pin=%s prio=%d",
               (int)mode, cfg.n_threads_generation, cfg.n_threads_batch,
               cfg.n_batch,
               cfg.pin_to_perf_cores ? "perf" :
               (cfg.pin_to_eff_cores ? "eff" : "none"),
               (int)cfg.priority);

    return cfg;
}

int32_t tn_recommend_batch_size(int64_t model_size_bytes) {
    int64_t ram = tn_available_ram_bytes();
    if (ram <= 0) return 256; // safe default

    // Ratio of free RAM to model size determines batch budget
    double ratio = (double)ram / (double)(model_size_bytes > 0 ? model_size_bytes : 1);

    if (ratio < 1.5) return 64;   // very tight
    if (ratio < 2.0) return 128;  // tight
    if (ratio < 3.0) return 256;  // comfortable
    return 512;                    // plenty
}

int64_t tn_available_ram_bytes(void) {
#if defined(__ANDROID__) || defined(__linux__)
    FILE * f = fopen("/proc/meminfo", "r");
    if (!f) return -1;

    int64_t mem_available = -1;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        long long val = 0;
        if (sscanf(line, "MemAvailable: %lld kB", &val) == 1) {
            mem_available = (int64_t)val * 1024;
            break;
        }
    }
    fclose(f);
    return mem_available;
#else
    return -1;
#endif
}

int64_t tn_max_model_size(int64_t available_ram_bytes, int32_t n_ctx) {
    if (available_ram_bytes <= 0) return 0;

    // Reserve for KV cache: ~0.5 MB per 1024 ctx tokens (rough estimate for small models)
    int64_t kv_estimate = ((int64_t)n_ctx / 1024) * 512 * 1024;
    if (kv_estimate < 64 * 1024 * 1024) kv_estimate = 64 * 1024 * 1024; // min 64 MB

    // Reserve 200 MB for OS + app + scratch buffers
    int64_t overhead = 200LL * 1024 * 1024;

    int64_t budget = available_ram_bytes - kv_estimate - overhead;
    return budget > 0 ? budget : 0;
}
