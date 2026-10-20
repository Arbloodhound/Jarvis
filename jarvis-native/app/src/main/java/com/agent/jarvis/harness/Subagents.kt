package com.agent.jarvis.harness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * §4 — delegated work. The main agent is the coordinator: it spawns children, steers them, and
 * gets each result back as a [Handoff]. Children NEVER spawn their own — the context a child runs
 * with ([ChildContext]) has no spawn at all, so nesting stops at depth 1 by construction.
 *
 * Lifecycle: [spawn] → RUNNING → DONE | FAILED; [interrupt] → INTERRUPTED (task and messages kept),
 * [resume] → RUNNING again on the same task; [close] → CLOSED (stops, no handoff). [send] queues a
 * message the child reads from its inbox. [list] is a snapshot — there is nothing to poll: a finished
 * child calls [onHandoff] once.
 *
 * The parent verifies before using a result: [verify] runs on every DONE handoff and its verdict
 * travels with it ([Handoff.verified], [Handoff.verifyNote]); an unverified result is marked so.
 * The runner (an LLM loop) is supplied by the app; this class is the orchestration only. JVM-tested.
 */
interface AgentRunner {
    /** Do [task]; read steering messages from [inbox]; return the result text. */
    suspend fun run(task: String, inbox: ReceiveChannel<String>, ctx: ChildContext): String
}

/** What a child may do besides its own work: report progress. Deliberately no spawn. */
class ChildContext internal constructor(val childId: String, private val progress: (String, String) -> Unit) {
    fun report(note: String) = progress(childId, note)
}

enum class ChildStatus { RUNNING, DONE, FAILED, INTERRUPTED, CLOSED }

data class ChildInfo(val id: String, val task: String, val status: ChildStatus, val lastNote: String, val startedAtMs: Long)

data class Handoff(val id: String, val task: String, val status: ChildStatus, val result: String?,
                   val error: String?, val verified: Boolean, val verifyNote: String)

class SubagentOrchestrator(
    private val scope: CoroutineScope,
    private val onHandoff: (Handoff) -> Unit,
    /** The parent's check of a finished result: (ok, note). Default: unverified, said so. */
    private val verify: suspend (task: String, result: String) -> Pair<Boolean, String> = { _, _ -> false to "not verified" },
    private val maxChildren: Int = 8,
) {
    private class Child(val id: String, val task: String, val runner: AgentRunner, val startedAtMs: Long) {
        @Volatile var status = ChildStatus.RUNNING
        @Volatile var lastNote = ""
        var job: Job? = null
        var inbox = Channel<String>(Channel.UNLIMITED)
        val pending = ArrayList<String>()
    }

    private val children = java.util.concurrent.ConcurrentHashMap<String, Child>()

    /** Start a child on [task]. Only the coordinator calls this; a child has no way to. */
    fun spawn(task: String, runner: AgentRunner, nowMs: Long = System.currentTimeMillis()): String {
        require(task.isNotBlank()) { "a child needs a task" }
        check(children.values.count { it.status == ChildStatus.RUNNING } < maxChildren) { "too many children running" }
        val c = Child("agent:" + java.util.UUID.randomUUID().toString().take(8), task, runner, nowMs)
        children[c.id] = c
        launch(c)
        return c.id
    }

    private fun launch(c: Child) {
        c.status = ChildStatus.RUNNING
        val ctx = ChildContext(c.id) { id, note -> children[id]?.lastNote = note }
        c.job = scope.launch {
            val out = try {
                Result.success(c.runner.run(c.task, c.inbox, ctx))
            } catch (e: CancellationException) {
                return@launch // interrupt or close: their own status, no handoff
            } catch (e: Exception) {
                Result.failure(e)
            }
            // Interrupted or closed while the runner was finishing (it can return normally after the
            // cancel, e.g. awaiting something already done): that run hands nothing off.
            if (!isActive) return@launch
            // The status changes only after the handoff is delivered: a child listed as DONE or
            // FAILED has always handed its result to the parent already.
            out.onSuccess { r ->
                val (ok, note) = try { verify(c.task, r) } catch (e: Exception) {
                    if (e is CancellationException) throw e; false to "verification failed: ${e.message}" }
                onHandoff(Handoff(c.id, c.task, ChildStatus.DONE, r, null, ok, note))
                c.status = ChildStatus.DONE
            }.onFailure { e ->
                onHandoff(Handoff(c.id, c.task, ChildStatus.FAILED, null, e.message ?: e.javaClass.simpleName, false, "failed"))
                c.status = ChildStatus.FAILED
            }
        }
    }

    /** Queue a steering message. False when the child isn't running. */
    fun send(id: String, message: String): Boolean {
        val c = children[id] ?: return false
        synchronized(c) { c.pending += message }
        return c.status == ChildStatus.RUNNING && c.inbox.trySend(message).isSuccess
    }

    /** Stop the child's current work; keep its task and messages for [resume]. */
    fun interrupt(id: String): Boolean {
        val c = children[id] ?: return false
        if (c.status != ChildStatus.RUNNING) return false
        c.status = ChildStatus.INTERRUPTED
        c.job?.cancel()
        return true
    }

    /** Restart an interrupted child on its own task; earlier messages are re-delivered. */
    fun resume(id: String): Boolean {
        val c = children[id] ?: return false
        if (c.status != ChildStatus.INTERRUPTED) return false
        c.inbox = Channel(Channel.UNLIMITED)
        synchronized(c) { c.pending.forEach { c.inbox.trySend(it) } }
        launch(c)
        return true
    }

    /** Stop one child for good. No handoff follows. */
    fun close(id: String): Boolean {
        val c = children[id] ?: return false
        if (c.status == ChildStatus.CLOSED) return false
        c.status = ChildStatus.CLOSED
        c.job?.cancel()
        c.inbox.close()
        return true
    }

    fun list(): List<ChildInfo> = children.values.sortedBy { it.startedAtMs }
        .map { ChildInfo(it.id, it.task, it.status, it.lastNote, it.startedAtMs) }
}
