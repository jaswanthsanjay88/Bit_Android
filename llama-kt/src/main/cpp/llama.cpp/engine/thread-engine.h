#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    TN_THREAD_POWER_SAVING = 0,
    TN_THREAD_BALANCED     = 1,
    TN_THREAD_PERFORMANCE  = 2,
} tn_thread_mode;

typedef enum {
    TN_PRIO_LOW      = -1,
    TN_PRIO_NORMAL   =  0,
    TN_PRIO_MEDIUM   =  1,
    TN_PRIO_HIGH     =  2,
    TN_PRIO_REALTIME =  3,
} tn_thread_priority;

typedef struct {
    int32_t  n_cores_total;
    int32_t  n_perf_cores;
    int32_t  n_efficiency_cores;
    int32_t  max_freq_khz;
    int32_t  min_freq_khz;
} tn_device_info;

// Maximum CPU mask width. ARM big.LITTLE phones today use 8 cores; reserve
// 64 to match ggml's GGML_MAX_N_THREADS without coupling the headers.
#define TN_MAX_CPUS 64

typedef struct {
    int32_t  n_threads_generation;
    int32_t  n_threads_batch;
    int32_t  n_batch;

    // pin_to_perf_cores is the legacy boolean knob; pin_to_eff_cores is its
    // mirror image for POWER_SAVING. Either may be true but not both. When
    // true, the corresponding cpumask is populated and used to construct an
    // explicit ggml_threadpool with affinity. When both are false, ggml gets
    // a default threadpool and the kernel scheduler picks placement.
    bool                pin_to_perf_cores;
    bool                pin_to_eff_cores;

    // Per-mode thread priority. POWER_SAVING uses LOW (yields to UI work),
    // BALANCED uses NORMAL, PERFORMANCE uses HIGH (real-time-ish on Android,
    // but not SCHED_FIFO — that requires CAP_SYS_NICE we don't have).
    tn_thread_priority  priority;

    // poll level passed to ggml_threadpool. 0 = OS futex wait between batches
    // (best for mobile: no power burn). >0 = busy-poll, only useful for
    // micro-batched server workloads.
    int32_t             poll;

    int32_t  perf_core_ids[16];
    int32_t  n_perf_core_ids;
    int32_t  efficiency_core_ids[16];
    int32_t  n_efficiency_core_ids;

    // CPU mask for the decode threadpool (bool-per-cpu, ggml convention).
    // Populated from perf_core_ids or efficiency_core_ids depending on the
    // pin_* flags above. Zeros mean "no affinity" — ggml falls back to its
    // default behavior of letting the OS schedule freely.
    bool                cpumask_generation[TN_MAX_CPUS];
    bool                cpumask_batch[TN_MAX_CPUS];
} tn_thread_config;

// Thermal state surfaced by power-engine. throttling_level is the highest
// (hottest) zone's mapped severity:
//   0 = COOL (< 60 C)
//   1 = WARM (60-75 C)
//   2 = HOT  (75-85 C) — auto-mode drops PERFORMANCE -> BALANCED
//   3 = CRITICAL (>= 85 C) — auto-mode drops to POWER_SAVING
typedef struct {
    int32_t  max_temp_milli_c;     // hottest zone reading, milli-Celsius
    int32_t  throttling_level;     // 0..3
    int32_t  n_zones_read;         // how many /sys/class/thermal zones we saw
    int32_t  battery_temp_milli_c; // -1 if unreadable
} tn_thermal_state;

// Detect device topology (reads /sys/devices/system/cpu/ on Android/Linux)
tn_device_info   tn_detect_device(void);

// Get thread config for a given mode
tn_thread_config tn_thread_config_for_mode(tn_thread_mode mode);

// Get recommended batch size based on available memory
int32_t          tn_recommend_batch_size(int64_t model_size_bytes);

// Model sizing: max model bytes that fits in available_ram_bytes with room for KV + overhead
int64_t          tn_max_model_size(int64_t available_ram_bytes, int32_t n_ctx);

// Query available RAM on the device
int64_t          tn_available_ram_bytes(void);

#ifdef __cplusplus
}
#endif
