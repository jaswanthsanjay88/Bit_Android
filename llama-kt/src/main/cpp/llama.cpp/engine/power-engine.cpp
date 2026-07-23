#include "power-engine.h"
#include "tn-log.h"

#include <cstdio>
#include <cstring>
#include <algorithm>

#if defined(__linux__) || defined(__ANDROID__)
#include <dirent.h>
#include <unistd.h>
#endif

// Per-level thresholds in milli-Celsius. These are the SoC-temperature points
// at which the power-engine will recommend dropping a tier. Defaults tuned
// for Snapdragon 7-class SoCs; tune via tn_power_set_thresholds() if you've
// profiled a specific device's throttle curve.
static int32_t g_warm_milli_c     = 60000;
static int32_t g_hot_milli_c      = 75000;
static int32_t g_critical_milli_c = 85000;

void tn_power_set_thresholds(int32_t warm_milli_c,
                             int32_t hot_milli_c,
                             int32_t critical_milli_c) {
    auto clamp = [](int32_t v) {
        if (v < 30000)  return 30000;
        if (v > 110000) return 110000;
        return v;
    };
    if (warm_milli_c     > 0) g_warm_milli_c     = clamp(warm_milli_c);
    if (hot_milli_c      > 0) g_hot_milli_c      = clamp(hot_milli_c);
    if (critical_milli_c > 0) g_critical_milli_c = clamp(critical_milli_c);
    TN_LOG_INF("power-engine thresholds: warm=%d hot=%d crit=%d (mC)",
               g_warm_milli_c, g_hot_milli_c, g_critical_milli_c);
}

#if defined(__linux__) || defined(__ANDROID__)

static int read_sysfs_int_pe(const char * path) {
    FILE * f = fopen(path, "r");
    if (!f) return -1;
    int v = -1;
    if (fscanf(f, "%d", &v) != 1) v = -1;
    fclose(f);
    return v;
}

static bool read_sysfs_string_pe(const char * path, char * out, size_t out_size) {
    FILE * f = fopen(path, "r");
    if (!f) return false;
    if (!fgets(out, (int)out_size, f)) { fclose(f); out[0] = 0; return false; }
    fclose(f);
    // Strip trailing newline
    size_t len = strlen(out);
    while (len > 0 && (out[len - 1] == '\n' || out[len - 1] == '\r')) {
        out[--len] = 0;
    }
    return len > 0;
}

// Common substrings that mark a thermal zone as battery-related across the
// vendor zoo (Qualcomm, MediaTek, Samsung, Google). Matched case-insensitively
// against the zone's "type" file.
static bool zone_is_battery(const char * type) {
    if (!type || !*type) return false;
    char lower[64];
    size_t n = std::min(strlen(type), sizeof(lower) - 1);
    for (size_t i = 0; i < n; i++) {
        char c = type[i];
        if (c >= 'A' && c <= 'Z') c = (char)(c - 'A' + 'a');
        lower[i] = c;
    }
    lower[n] = 0;
    return strstr(lower, "batt")    != nullptr
        || strstr(lower, "bat_")    != nullptr
        || strstr(lower, "vts")     != nullptr
        || strstr(lower, "fuelgauge") != nullptr;
}

// Skip non-CPU/SoC zones whose temps don't reflect compute headroom (skin
// sensors, charger, etc.). Returning true here means "include in max temp".
static bool zone_is_compute(const char * type) {
    if (!type || !*type) return false;
    char lower[64];
    size_t n = std::min(strlen(type), sizeof(lower) - 1);
    for (size_t i = 0; i < n; i++) {
        char c = type[i];
        if (c >= 'A' && c <= 'Z') c = (char)(c - 'A' + 'a');
        lower[i] = c;
    }
    lower[n] = 0;
    // Skin / charger / battery sensors don't track our throttle curve.
    if (strstr(lower, "skin")    != nullptr) return false;
    if (strstr(lower, "ambient") != nullptr) return false;
    if (strstr(lower, "charger") != nullptr) return false;
    if (zone_is_battery(lower))              return false;
    // Anything else — cpuss, gpuss, npu, modem, lpass, mdm, soc — is fair game.
    return true;
}

#endif // __linux__ || __ANDROID__

tn_power_state tn_power_get_thermal_state(void) {
    tn_power_state s = {};
    s.max_temp_milli_c     = -1;
    s.battery_temp_milli_c = -1;
    s.throttling_level     =  0;
    s.n_zones_read         =  0;

#if defined(__linux__) || defined(__ANDROID__)
    DIR * d = opendir("/sys/class/thermal");
    if (!d) return s;

    struct dirent * e;
    int max_temp = -1;
    int batt_temp = -1;
    int n = 0;

    while ((e = readdir(d)) != nullptr) {
        // Look for thermal_zoneN entries.
        int zone_id = -1;
        if (sscanf(e->d_name, "thermal_zone%d", &zone_id) != 1) continue;
        if (zone_id < 0) continue;

        char type_path[160], temp_path[160];
        snprintf(type_path, sizeof(type_path), "/sys/class/thermal/%s/type", e->d_name);
        snprintf(temp_path, sizeof(temp_path), "/sys/class/thermal/%s/temp", e->d_name);

        char type[64];
        if (!read_sysfs_string_pe(type_path, type, sizeof(type))) continue;

        int t = read_sysfs_int_pe(temp_path);
        if (t < 0) continue;

        // Some devices report deci-Celsius (e.g. 480 = 48.0 C) instead of milli.
        // Heuristic: if the value is < 200, scale up by 100; if > 200000, treat
        // as a sensor error and skip.
        if (t > 0 && t < 200)         t *= 100;   // deci -> milli (rough)
        if (t > 200000)               continue;   // implausible

        n++;
        if (zone_is_battery(type)) {
            // Battery zone: tracked separately, not folded into the SoC max.
            if (batt_temp < 0 || t > batt_temp) batt_temp = t;
        } else if (zone_is_compute(type)) {
            if (t > max_temp) max_temp = t;
        }
    }
    closedir(d);

    s.max_temp_milli_c     = max_temp;
    s.battery_temp_milli_c = batt_temp;
    s.n_zones_read         = n;

    if      (max_temp < 0)                     s.throttling_level = 0;
    else if (max_temp >= g_critical_milli_c)   s.throttling_level = 3;
    else if (max_temp >= g_hot_milli_c)        s.throttling_level = 2;
    else if (max_temp >= g_warm_milli_c)       s.throttling_level = 1;
    else                                       s.throttling_level = 0;
#endif

    return s;
}

tn_thread_mode tn_power_recommend_mode(tn_thread_mode requested,
                                       const tn_power_state * state) {
    if (!state) return requested;

    switch (state->throttling_level) {
        case 0: // COOL — give the user what they asked for
        case 1: // WARM — still within nominal envelope
            return requested;
        case 2: // HOT  — cap at BALANCED so PERFORMANCE pinning doesn't worsen the climb
            return requested == TN_THREAD_PERFORMANCE ? TN_THREAD_BALANCED : requested;
        case 3: // CRITICAL — drop to POWER_SAVING regardless of request
            return TN_THREAD_POWER_SAVING;
        default:
            return requested;
    }
}
