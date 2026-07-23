#include "tn-log.h"

#include <atomic>
#include <mutex>
#include <cstdarg>
#include <cstdio>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

// Mutex guards callback + user_data pair so they are always updated together
static std::mutex            g_log_mutex;
static tn_log_callback       g_callback  = nullptr;
static void *                g_user_data = nullptr;
static std::atomic<int32_t>  g_max_level{TN_LOG_LEVEL_INFO};

void tn_log_set_callback(tn_log_callback cb, void * user_data) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_callback  = cb;
    g_user_data = user_data;
}

void tn_log_set_level(enum tn_log_level max_level) {
    g_max_level.store(static_cast<int32_t>(max_level), std::memory_order_relaxed);
}

void tn_log_write(enum tn_log_level level, const char * tag, const char * fmt, ...) {
    if (static_cast<int32_t>(level) > g_max_level.load(std::memory_order_relaxed)) {
        return;
    }

    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    // Snapshot callback + user_data under lock so they are always a matched pair
    tn_log_callback cb;
    void * ud;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        cb = g_callback;
        ud = g_user_data;
    }

    if (cb) {
        cb(level, tag, buf, ud);
        return;
    }

#if defined(__ANDROID__)
    static const int prio[] = {
        ANDROID_LOG_ERROR,
        ANDROID_LOG_WARN,
        ANDROID_LOG_INFO,
        ANDROID_LOG_DEBUG,
    };
    int idx = (level >= 0 && level <= 3) ? level : 2;
    __android_log_print(prio[idx], "tn-engine", "%s", buf);
#else
    static const char * prefix[] = { "E", "W", "I", "D" };
    int idx = (level >= 0 && level <= 3) ? level : 2;
    FILE * out = (level <= TN_LOG_LEVEL_WARN) ? stderr : stdout;
    fprintf(out, "[%s] %s", prefix[idx], buf);
    fflush(out);
#endif
}
