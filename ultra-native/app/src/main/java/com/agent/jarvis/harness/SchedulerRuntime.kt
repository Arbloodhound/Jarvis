package com.agent.jarvis.harness

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Android firing for [Scheduler] / [JobBook] (§0 wire-in). One inexact alarm
 * ([AlarmManager.setAndAllowWhileIdle]: no exact-alarm permission, Doze may delay it by minutes) wakes
 * [SchedulerReceiver], which runs one [JobBook.tick] and re-arms the alarm for the next due job. Also
 * re-armed at app start and after boot. AlarmManager rather than WorkManager: WorkManager isn't a
 * dependency of this app and the tick is short.
 *
 * Modules register a handler by job id or owner ("harness:feed", "goal:"…), optionally with the job
 * itself ([register]). Jobs are only added when a module that runs them registers: the default
 * harness jobs ([Scheduler.defaultHarnessJobs]) are not seeded blindly, so the phone is never woken
 * every half hour for work nothing performs. A job left without a handler (its module removed) runs
 * as "no handler": logged, marked run, never a busy loop.
 *
 * RUNTIME-UNPROVEN: alarm delivery timing on the phone (Doze, GrapheneOS) has not been observed.
 */
object SchedulerRuntime {
    private const val TAG = "UltraScheduler"
    const val ACTION_TICK = "com.agent.jarvis.harness.TICK"

    private val handlers = java.util.concurrent.ConcurrentHashMap<String, suspend (CronJob) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun book(ctx: Context) = JobBook(File(ctx.filesDir, "harness/jobs.json"))

    /**
     * [key] = a job id, an owner ("harness"), or an owner prefix ending in ':' ("goal:"). With [job],
     * that job is added to the book if it isn't there yet (first run one period from now, not a burst
     * at start-up) and the alarm is re-armed.
     */
    fun register(ctx: Context, key: String, job: CronJob? = null, handler: suspend (CronJob) -> Unit) {
        handlers[key] = handler
        if (job != null) {
            val b = book(ctx)
            if (b.load().none { it.id == job.id }) b.put(job.copy(lastRunMs = System.currentTimeMillis()))
            arm(ctx, JobBook.nextWake(b.load(), System.currentTimeMillis()))
        }
    }

    /** App start: arm the next wake for the jobs already in the book. Never throws. */
    fun start(ctx: Context) {
        try {
            arm(ctx, JobBook.nextWake(book(ctx).load(), System.currentTimeMillis()))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "start failed: ${e.message}")
        }
    }

    /** Add or replace a job and re-arm. */
    fun schedule(ctx: Context, job: CronJob) {
        val b = book(ctx)
        b.put(job)
        arm(ctx, JobBook.nextWake(b.load(), System.currentTimeMillis()))
    }

    fun cancel(ctx: Context, id: String): Boolean {
        val b = book(ctx)
        val removed = b.remove(id)
        arm(ctx, JobBook.nextWake(b.load(), System.currentTimeMillis()))
        return removed
    }

    internal fun tick(ctx: Context, done: () -> Unit) {
        scope.launch {
            try {
                val (outcomes, next) = book(ctx).tick(System.currentTimeMillis(), handlers)
                outcomes.forEach { android.util.Log.i(TAG, "job ${it.jobId}: ${it.result}") }
                arm(ctx, next)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "tick failed: ${e.message}")
            } finally { done() }
        }
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, SchedulerReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun arm(ctx: Context, atMs: Long?) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pending(ctx)
        if (atMs == null) { am.cancel(pi); return }
        // At least a minute out: a job that is due "now" fires on the next tick, never in a loop.
        val at = maxOf(atMs, System.currentTimeMillis() + 60_000L)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

/** Receives the scheduler alarm (and boot) and runs one tick. Registered in the manifest. */
class SchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SchedulerRuntime.ACTION_TICK -> {
                val pending = goAsync()
                SchedulerRuntime.tick(context.applicationContext) { pending.finish() }
            }
            Intent.ACTION_BOOT_COMPLETED -> SchedulerRuntime.start(context.applicationContext)
        }
    }
}
