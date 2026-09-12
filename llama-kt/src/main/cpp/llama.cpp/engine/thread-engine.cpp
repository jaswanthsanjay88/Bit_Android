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

    int online_cores = 4;
#ifdef _SC_NPROCESSORS_ONLN
    online_cores = (int)sysconf(_SC_NPROCESSORS_ONLN);
    if (online_cores < 1) online_cores = 4;
#endif

#if defined(__linux__) || defined(__ANDROID__)
    DIR * dir = opendir("/sys/devices/system/cpu");
    int freqs[64] = {};
    int n_cores = 0;

    if (dir) {
        struct dirent * entry;
        while ((entry = readdir(dir)) != nullptr && n_cores < 64) {
            int cpu_id = -1;
            if (sscanf(entry->d_name, "cpu%d", &cpu_id) == 1 && cpu_id >= 0 && cpu_id < 64) {
                if (!core_is_online(cpu_id)) continue;
                freqs[n_cores] = core_max_freq_khz(cpu_id);
                n_cores++;
            }
        }
        closedir(dir);
    }

    // On Android, untrusted apps are blocked by SELinux from reading /sys/devices/system/cpu/cpu*/online
    // causing n_cores to report as 1. Fall back to sysconf(_SC_NPROCESSORS_ONLN).
    if (n_cores < online_cores) {
        info.n_cores_total = online_cores;
        info.n_perf_cores = std::min(4, std::max(2, online_cores / 2));
        info.n_efficiency_cores = online_cores - info.n_perf_cores;
        TN_LOG_INF("device (sysconf fallback): %d cores (%d perf, %d eff)",
                   info.n_cores_total, info.n_perf_cores, info.n_efficiency_cores);
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
    info.n_cores_total = online_cores;
    info.n_perf_cores = std::min(4, std::max(2, online_cores / 2));
    info.n_efficiency_cores = online_cores - info.n_perf_cores;
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

    // If sysfs scanning failed or didn't find all cores, disable core pinning
    // so we never restrict execution to a single core or accidentally pin to CPU 0 (the little core).
    if (cfg.n_perf_core_ids < dev.n_perf_cores || cfg.n_perf_core_ids <= 1) {
        cfg.n_perf_core_ids = dev.n_perf_cores;
        cfg.n_efficiency_core_ids = dev.n_efficiency_cores;
    }
    const bool can_pin = (n >= dev.n_cores_total && n > 1 && cfg.n_perf_core_ids > 1);

    int np      = cfg.n_perf_core_ids > 0 ? cfg.n_perf_core_ids : dev.n_cores_total;
    int ne      = cfg.n_efficiency_core_ids;
    int n_total = dev.n_cores_total > 0 ? dev.n_cores_total : 4;
    if (np <= 0) np = 4;

    // Default knobs shared across modes. Polling=0 because we always block on
    // user input between decodes — busy-spinning burns battery for nothing.
    cfg.poll = 0;

    switch (mode) {
        case TN_THREAD_POWER_SAVING:
            cfg.n_threads_generation = 2;
            cfg.n_threads_batch      = ne > 0 ? std::min(2, ne) : std::min(2, np);
            cfg.n_batch              = 128;
            cfg.pin_to_perf_cores    = false;
            cfg.pin_to_eff_cores     = can_pin && (ne > 0);
            cfg.priority             = TN_PRIO_LOW;
            if (cfg.pin_to_eff_cores && ne > 0) {
                fill_cpumask(cfg.cpumask_generation, cfg.efficiency_core_ids, ne);
                fill_cpumask(cfg.cpumask_batch,      cfg.efficiency_core_ids, ne);
            }
            break;

        case TN_THREAD_BALANCED:
            // 4 generation threads on mobile (matching arm/ai-chat)
            cfg.n_threads_generation = std::max(2, std::min(4, np));
            cfg.n_threads_batch      = std::min(6, n_total);
            cfg.n_batch              = 512;
            cfg.pin_to_perf_cores    = can_pin;
            cfg.pin_to_eff_cores     = false;
            cfg.priority             = TN_PRIO_NORMAL;
            if (can_pin) {
                fill_cpumask(cfg.cpumask_generation, cfg.perf_core_ids, np);
                fill_cpumask(cfg.cpumask_batch,      cfg.perf_core_ids, np);
            }
            break;

        case TN_THREAD_PERFORMANCE:
            // 4 generation threads, up to 8 threads for prompt processing
            cfg.n_threads_generation = std::max(3, std::min(4, np));
            cfg.n_threads_batch      = std::min(8, n_total);
            cfg.n_batch              = 512;
            cfg.pin_to_perf_cores    = can_pin;
            cfg.pin_to_eff_cores     = false;
            cfg.priority             = TN_PRIO_HIGH;
            if (can_pin) {
                fill_cpumask(cfg.cpumask_generation, cfg.perf_core_ids, np);
                fill_cpumask(cfg.cpumask_batch,      cfg.perf_core_ids, np);
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
