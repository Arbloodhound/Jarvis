// Host stand-in for Android's <android/log.h>, so ultra_llm.cpp builds as a host JNI
// library for the harness. Lines go to stderr as "[<tag>] <message>"; run.sh counts the
// [UltraLlm] ones.
#pragma once
#include <stdarg.h>
#include <stdio.h>

enum { ANDROID_LOG_VERBOSE = 2, ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4,
       ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };

static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void) prio;
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
    return 0;
}
