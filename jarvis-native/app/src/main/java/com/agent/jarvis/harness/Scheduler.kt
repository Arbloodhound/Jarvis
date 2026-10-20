package com.agent.jarvis.harness

/**
 * Schedule plane: cron (time-based) + hooks (event-based).
 *
 * The repo has no scheduler (checked all 332 Kotlin files). This is the
 * policy model: owners, due computation, wake routing. On Android the
 * firing itself is wired to WorkManager/AlarmManager by the repo session;
 * this class answers "what is due" and "who owns it".
 *
 * Every job has an owner (goal:<slug>, item:<slug>, or "harness").
 * A reminder cron with no owner is a routing error.
 */
data class CronJob(
    val id: String,
    val task: String,
    val owner: String,
    /** interval|daily|runonce */
    val kind: String = "interval",
    val everySecs: Long = 3600,
    /** runonce: epoch millis to fire */
    val atMs: Long = 0L,
    val enabled: Boolean = true,
    val lastRunMs: Long = 0L,
    /** owner identity: who may force-run, who receives delivery,
     * whose tool policy applies */
    val ownerAgent: String = "",
    val ownerSession: String = "",
) {
    init {
        require(owner.isNotEmpty()) { "cron job needs an owner" }
        require(kind in setOf("interval", "daily", "runonce")) { "bad kind: $kind" }
    }

    fun due(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return false
        return when (kind) {
            "runonce" -> nowMs >= atMs && lastRunMs == 0L
            "interval" -> nowMs - lastRunMs >= everySecs * 1000
            "daily" -> nowMs - lastRunMs >= 86_400_000L
            else -> false
        }
    }
}

/**
 * Event hook: poll a cheap condition; wake an agent only when something
 * relevant changed. New hooks start disabled — test the condition first.
 */
class Hook(
    val id: String,
    val task: String,
    val pollSecs: Long = 60,
    val condition: () -> Pair<Boolean, Map<String, String>>,
    var enabled: Boolean = false,
) {
    var lastPollMs: Long = 0L
}

class Scheduler {
    private val jobs = mutableMapOf<String, CronJob>()
    private val hooks = mutableMapOf<String, Hook>()

    fun addJob(job: CronJob): String {
        jobs[job.id] = job
        return job.id
    }

    fun removeJob(id: String): Boolean = jobs.remove(id) != null

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val j = jobs[id] ?: return false
        jobs[id] = j.copy(enabled = enabled)
        return true
    }

    fun dueJobs(nowMs: Long = System.currentTimeMillis()): List<CronJob> =
        jobs.values.filter { it.due(nowMs) }

    fun markRun(id: String, nowMs: Long = System.currentTimeMillis()) {
        jobs[id]?.let { jobs[id] = it.copy(lastRunMs = nowMs) }
    }

    fun addHook(hook: Hook): String {
        hooks[hook.id] = hook
        return hook.id
    }

    fun enableHook(id: String): Boolean {
        val h = hooks[id] ?: return false
        h.enabled = true
        return true
    }

    /** Returns (hookId, payload) wakes. A throwing condition stays silent. */
    fun pollHooks(nowMs: Long = System.currentTimeMillis()): List<Pair<String, Map<String, String>>> {
        val wakes = mutableListOf<Pair<String, Map<String, String>>>()
        for (h in hooks.values) {
            if (!h.enabled) continue
            if (nowMs - h.lastPollMs < h.pollSecs * 1000) continue
            h.lastPollMs = nowMs
            val (wake, payload) = try { h.condition() } catch (_: Exception) { continue }
            if (wake) wakes += h.id to payload
        }
        return wakes
    }

    companion object {
        /** The background jobs the harness runs for itself. */
        fun defaultHarnessJobs(s: Scheduler) {
            s.addJob(CronJob(id = "harness:memory-upkeep", owner = "harness",
                task = "memory upkeep: consolidate new signal"))
            s.addJob(CronJob(id = "harness:heartbeat", owner = "harness",
                task = "heartbeat: liveness check", everySecs = 1800))
            s.addJob(CronJob(id = "harness:relationships", owner = "harness",
                task = "relationship pages refresh"))
            s.addJob(CronJob(id = "harness:ideas", owner = "harness",
                task = "idea curation", kind = "daily"))
            s.addJob(CronJob(id = "harness:skill-review", owner = "harness",
                task = "skill review", kind = "daily"))
            s.addJob(CronJob(id = "harness:dreaming", owner = "harness",
                task = "goal research + dreaming", kind = "daily"))
        }
    }
}
