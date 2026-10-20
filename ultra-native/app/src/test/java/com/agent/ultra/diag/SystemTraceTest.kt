package com.agent.ultra.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemTraceTest {
    // pid (comm) state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime …
    private fun stat(name: String, utime: Long, stime: Long) =
        "123 ($name) R 1 1 0 0 -1 4194560 100 0 5 0 $utime $stime 0 0 20 0 1 0"

    @Test fun readsNameAndCpuTime() {
        assertEquals(SystemTrace.Ticks("ggml) worker", 150), SystemTrace.parseStat(stat("ggml) worker", 100, 50)))
        assertNull(SystemTrace.parseStat("garbage"))
    }

    @Test fun busiestGroupsThreadsByNameSinceLastPoll() {
        val prev = mapOf(1 to SystemTrace.Ticks("ggml", 100), 2 to SystemTrace.Ticks("ggml", 100), 3 to SystemTrace.Ticks("whisper", 10))
        val now = mapOf(1 to SystemTrace.Ticks("ggml", 300), 2 to SystemTrace.Ticks("ggml", 250), 3 to SystemTrace.Ticks("whisper", 60),
            4 to SystemTrace.Ticks("new", 999))
        assertEquals(listOf("ggml 3.5s", "whisper 0.5s"), SystemTrace.busiest(prev, now))
    }
}
