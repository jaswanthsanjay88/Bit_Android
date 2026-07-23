#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum tn_log_level : int32_t {
    TN_LOG_LEVEL_ERROR = 0,
    TN_LOG_LEVEL_WARN  = 1,
    TN_LOG_LEVEL_INFO  = 2,
    TN_LOG_LEVEL_DEBUG = 3,
};

// Set to NULL for default (Android logcat or stderr)
typedef void (*tn_log_callback)(enum tn_log_level level, const char * tag, const char * msg, void * user_data);

void tn_log_set_callback(tn_log_callback cb, void * user_data);
void tn_log_set_level(enum tn_log_level max_level);

// printf format attribute
#ifndef __GNUC__
#    define TN_LOG_ATTR(...)
#elif defined(__MINGW32__) && !defined(__clang__)
#    define TN_LOG_ATTR(...) __attribute__((format(gnu_printf, __VA_ARGS__)))
#else
#    define TN_LOG_ATTR(...) __attribute__((format(printf, __VA_ARGS__)))
#endif

TN_LOG_ATTR(3, 4)
void tn_log_write(enum tn_log_level level, const char * tag, const char * fmt, ...);

#ifdef __cplusplus
}
#endif

#define TN_LOG_TAG __FILE__

#define TN_LOG_ERR(...) tn_log_write(TN_LOG_LEVEL_ERROR, TN_LOG_TAG, __VA_ARGS__)
#define TN_LOG_WRN(...) tn_log_write(TN_LOG_LEVEL_WARN,  TN_LOG_TAG, __VA_ARGS__)
#define TN_LOG_INF(...) tn_log_write(TN_LOG_LEVEL_INFO,  TN_LOG_TAG, __VA_ARGS__)
#define TN_LOG_DBG(...) tn_log_write(TN_LOG_LEVEL_DEBUG, TN_LOG_TAG, __VA_ARGS__)
