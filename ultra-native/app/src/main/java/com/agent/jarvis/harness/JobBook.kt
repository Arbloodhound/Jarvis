package com.agent.jarvis.harness

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable side of [Scheduler]: the cron jobs saved to a JSON file so they survive the process, and
 * the one decision the Android firing layer ([SchedulerRuntime]) needs: what runs now, and when to
 * wake next. Pure apart from the file; JVM-tested.
 *
 * Firing contract (one tick):
 *  1. load the book, run every due job through its handler (by id, then by owner prefix),
 *  2. mark each job run (a runonce job is then disabled: it never fires twice),
 *  3. a job with no handler is still marked run and reported as "no handler" — never silently
 *     re-fired every tick,
 *  4. save, and schedule the next wake at the earliest [nextDueAt] of the enabled jobs.
 * A handler that throws is reported as failed and still marked run: a failing job is retried on its
 * next period, not in a tight loop.
 */
class JobBook(private val file: File) {

    fun load(): MutableList<CronJob> {
        if (!file.isFile) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    fun save(jobs: List<CronJob>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray().apply { jobs.forEach { put(toJson(it)) } }.toString())
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    /** Add or replace a job by id. */
    fun put(job: CronJob) {
        val all = load()
        all.removeAll { it.id == job.id }
        all += job
        save(all)
    }

    fun remove(id: String): Boolean {
        val all = load()
        val removed = all.removeAll { it.id == id }
        if (removed) save(all)
        return removed
    }

    data class Outcome(val jobId: String, val result: String)

    /**
     * One tick at [nowMs]: runs the due jobs through [handlers] and saves. Returns what happened to
     * each due job and the next wake time (null when nothing is enabled).
     */
    suspend fun tick(nowMs: Long, handlers: Map<String, suspend (CronJob) -> Unit>): Pair<List<Outcome>, Long?> {
        val all = load()
        val outcomes = ArrayList<Outcome>()
        for ((i, job) in all.withIndex()) {
            if (!job.due(nowMs)) continue
            val h = handlers[job.id] ?: handlers[job.owner] ?: handlers[job.owner.substringBefore(':') + ":"]
            val result = if (h == null) "no handler" else try { h(job); "ran" } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "failed: ${e.javaClass.simpleName}"
            }
            all[i] = job.copy(lastRunMs = nowMs, enabled = job.enabled && job.kind != "runonce")
            outcomes += Outcome(job.id, result)
        }
        save(all)
        return outcomes to nextWake(all, nowMs)
    }

    companion object {
        /** When [job] is next due, or null when it never will be (disabled, runonce already run). */
        fun nextDueAt(job: CronJob): Long? {
            if (!job.enabled) return null
            return when (job.kind) {
                "runonce" -> if (job.lastRunMs == 0L) job.atMs else null
                "interval" -> job.lastRunMs + job.everySecs * 1000
                "daily" -> job.lastRunMs + 86_400_000L
                else -> null
            }
        }

        /** The earliest next due time, never earlier than [nowMs] (an overdue job fires now). */
        fun nextWake(jobs: List<CronJob>, nowMs: Long): Long? =
            jobs.mapNotNull(::nextDueAt).minOrNull()?.coerceAtLeast(nowMs)

        fun toJson(j: CronJob): JSONObject = JSONObject()
            .put("id", j.id).put("task", j.task).put("owner", j.owner).put("kind", j.kind)
            .put("everySecs", j.everySecs).put("atMs", j.atMs).put("enabled", j.enabled)
            .put("lastRunMs", j.lastRunMs).put("ownerAgent", j.ownerAgent).put("ownerSession", j.ownerSession)

        /** Null for a malformed entry (no owner, unknown kind): skipped, never guessed. */
        fun fromJson(o: JSONObject): CronJob? = try {
            CronJob(id = o.getString("id"), task = o.optString("task"), owner = o.optString("owner"),
                kind = o.optString("kind", "interval"), everySecs = o.optLong("everySecs", 3600),
                atMs = o.optLong("atMs"), enabled = o.optBoolean("enabled", true), lastRunMs = o.optLong("lastRunMs"),
                ownerAgent = o.optString("ownerAgent"), ownerSession = o.optString("ownerSession"))
        } catch (_: Exception) { null }
    }
}
