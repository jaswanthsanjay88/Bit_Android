#pragma once

#include "thread-engine.h"

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Power-engine sits on top of thread-engine. It reads thermal zones and
// battery temperature, classifies the device into one of four heat tiers,
// and (optionally) recommends a thread mode adjustment so a "PERFORMANCE"
// pin can de-rate to BALANCED when the SoC starts thermal-throttling
// instead of letting the OS govern us silently.
//
// Polling cadence is the caller's responsibility — typically every ~32-128
// decoded tokens. Reading sysfs temp files costs ~50 us each; on an 8-zone
// device the full sweep is well under 1 ms.

// Snapshot of the device's current thermal state. Populated by
// tn_power_get_thermal_state(). Use tn_power_classify_state() to map this
// into a recommended thread_mode adjustment.
//
// Re-export of the struct from thread-engine.h for convenience.
typedef tn_thermal_state tn_power_state;

// Read all /sys/class/thermal/thermal_zone*/temp + type entries and produce
// a snapshot. Lightweight (~100 us on an 8-zone device). Safe to call from
// any thread; the underlying sysfs reads are stateless.
tn_power_state tn_power_get_thermal_state(void);

// Given a current thread mode and a thermal snapshot, return the mode the
// power-engine recommends. Maps:
//   COOL  (level 0) -> requested mode unchanged
//   WARM  (level 1) -> requested mode unchanged (some headroom still)
//   HOT   (level 2) -> never above BALANCED
//   CRIT  (level 3) -> always POWER_SAVING
//
// This is a pure function — caller decides whether to actually apply the
// adjustment by re-calling apply_thread_mode(). Useful for the auto-mode
// loop to throttle without giving up sustained-throughput knobs entirely.
tn_thread_mode tn_power_recommend_mode(tn_thread_mode requested,
                                       const tn_power_state * state);

// Override the per-level temperature thresholds (milli-Celsius). Defaults:
//   warm_c     =  60000   (60.0 C)
//   hot_c      =  75000   (75.0 C)
//   critical_c =  85000   (85.0 C)
// Pass <= 0 for any field to keep the existing value. Values are clamped
// to [30 C, 110 C].
void tn_power_set_thresholds(int32_t warm_milli_c,
                             int32_t hot_milli_c,
                             int32_t critical_milli_c);

#ifdef __cplusplus
}
#endif
