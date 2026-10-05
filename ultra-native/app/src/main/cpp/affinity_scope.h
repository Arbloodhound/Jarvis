// Pins the CALLING thread to a CPU list for one scope, then unpins it. Shared by ultra_llm
// and ultra_whisper. The JNI callers are pooled Kotlin dispatcher threads, so a pin must
// never outlive the call that set it.
//
// "Unpinned" = every CPU, not the mask read at entry. Android confines app threads with
// cpusets, not affinity, so the mask read at entry is the cpuset of that moment (e.g. the
// little cores while backgrounded); writing it back would make it a sticky user affinity
// (kernels that keep one across cpuset moves) and hold the thread there after the app
// returns to the foreground. All CPUs is intersected with whatever cpuset applies now.
#pragma once
#include <sched.h>
#include <unistd.h>
#include <vector>

class AffinityScope {
public:
    // Empty list, no valid id, or a failed syscall: the thread is left exactly as it was.
    explicit AffinityScope(const std::vector<int> &cpus) {
        if (cpus.empty()) return;
        cpu_set_t want;
        CPU_ZERO(&want);
        for (int c : cpus) if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &want);
        if (CPU_COUNT(&want) == 0) return;
        if (sched_setaffinity(0, sizeof(want), &want) != 0) return;
        pinned_ = true;
    }
    ~AffinityScope() {
        if (!pinned_) return;
        cpu_set_t all;
        CPU_ZERO(&all);
        long n = sysconf(_SC_NPROCESSORS_CONF);
        if (n <= 0 || n > CPU_SETSIZE) n = CPU_SETSIZE;
        for (long c = 0; c < n; c++) CPU_SET(c, &all);
        sched_setaffinity(0, sizeof(all), &all);
    }
    AffinityScope(const AffinityScope &) = delete;
    AffinityScope &operator=(const AffinityScope &) = delete;

private:
    bool pinned_ = false;
};
