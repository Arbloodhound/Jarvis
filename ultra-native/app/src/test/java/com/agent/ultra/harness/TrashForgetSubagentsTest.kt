package com.agent.ultra.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrashForgetSubagentsTest {
    @get:Rule val tmp = TemporaryFolder()

    // ── §2 Trash ──
    private fun setup(): Triple<Trash, File, File> {
        val ws = tmp.newFolder("workspace")
        val trash = Trash(File(tmp.root, "trash"), listOf(ws))
        return Triple(trash, ws, File(ws, "notes.md").apply { writeText("hello") })
    }

    @Test fun moveListRestoreNewestFirst() {
        val (trash, ws, f) = setup()
        val g = File(ws, "b.txt").apply { writeText("b") }
        val id1 = trash.move(f, nowMs = 1)
        val id2 = trash.move(g, nowMs = 2)
        assertFalse(f.exists())
        assertEquals(listOf(id2, id1), trash.list().map { it.id })
        assertEquals(f.canonicalFile, trash.restore(id1).canonicalFile)
        assertEquals("hello", f.readText())
        assertEquals(listOf(id2), trash.list().map { it.id })
    }

    @Test fun restoreNeverOverwritesItGoesToRestored() {
        val (trash, ws, f) = setup()
        val id = trash.move(f)
        File(ws, "notes.md").writeText("a new file in the same place")
        val back = trash.restore(id)
        assertEquals("notes.md", back.name)
        assertTrue(back.path.contains("restored"))
        assertEquals("a new file in the same place", File(ws, "notes.md").readText())
        assertEquals("hello", back.readText())
    }

    @Test fun outsideTheWorkspaceIsRefusedAndTheAgentHasNoEmpty() {
        val (trash, _, _) = setup()
        val outside = tmp.newFile("system.cfg")
        assertTrue(runCatching { trash.move(outside) }.exceptionOrNull() is Trash.NotAllowed)
        assertTrue(outside.exists())
        val agentFacing: AgentTrash = trash
        assertFalse(agentFacing.javaClass.interfaces.flatMap { it.methods.toList() }.any { it.name.contains("empty", true) })
    }

    @Test fun settingsEmptyRemovesEverything() {
        val (trash, _, f) = setup()
        trash.move(f)
        assertEquals(1, TrashAdmin.empty(trash))
        assertTrue(trash.list().isEmpty())
    }

    // ── §3 Forget ──
    private class MemSource(override val name: String, val data: MutableMap<String, String>, val failIds: Set<String> = emptySet()) : ForgetSource {
        override suspend fun find(subject: String) = data.filter { mentions(it.value, subject) }.map { ForgetItem(name, it.key, it.value) }
        override suspend fun delete(items: List<ForgetItem>) = items.filter { it.id !in failIds && data.remove(it.id) != null }
        override val limit = "test limit"
    }

    @Test fun nothingIsDeletedBeforeConfirmationThenVerified() = runBlocking {
        val claims = MemSource("claims", mutableMapOf("c1" to "Sam's birthday is 3 May", "c2" to "likes tea"))
        val notes = MemSource("notes", mutableMapOf("1" to "buy a gift for Sam's birthday"))
        val f = Forget(listOf(claims, notes))
        val plan = f.plan("Sam's birthday", nowMs = 0)
        assertEquals(2, plan.items.size)
        assertTrue(plan.summary().contains("Nothing is deleted until you confirm"))
        assertTrue(plan.summary().contains("test limit"))
        assertEquals(2, claims.data.size + notes.data.size - 1) // untouched so far
        val report = f.execute(f.userConfirmed(plan.id), nowMs = 1_000)
        assertEquals(2, report.removed.size)
        assertTrue(report.stillFound.isEmpty())
        assertEquals(mapOf("c2" to "likes tea"), claims.data)
        assertTrue(report.summary().contains("best-effort"))
    }

    @Test fun aPartialFailureIsReportedHonestly() = runBlocking {
        val src = MemSource("claims", mutableMapOf("a" to "secret project x", "b" to "project x notes"), failIds = setOf("b"))
        val f = Forget(listOf(src))
        val r = f.execute(f.userConfirmed(f.plan("project x").id))
        assertEquals(listOf("b"), r.failed.map { it.id })
        assertEquals(listOf("b"), r.stillFound.map { it.id })
        assertTrue(r.summary().contains("Couldn't remove 1"))
    }

    @Test fun plansExpireAndCantRunTwice() = runBlocking {
        val f = Forget(listOf(MemSource("n", mutableMapOf("1" to "x y z"))))
        val p = f.plan("x", nowMs = 0)
        assertTrue(runCatching { f.execute(f.userConfirmed(p.id), nowMs = Forget.PLAN_TTL_MS + 1) }.isFailure)
        val p2 = f.plan("x", nowMs = 0)
        f.execute(f.userConfirmed(p2.id), nowMs = 1)
        assertTrue(runCatching { f.execute(f.userConfirmed(p2.id), nowMs = 2) }.isFailure)
    }

    @Test fun filesSourceFindsByNameOrContent() = runBlocking {
        val ws = tmp.newFolder("ws2")
        File(ws, "draft-to-sam.txt").writeText("hello")
        File(ws, "other.txt").writeText("mentions Sam inside")
        File(ws, "unrelated.txt").writeText("nothing")
        val src = FilesForgetSource(listOf(ws))
        assertEquals(2, src.find("sam").size)
        assertEquals(2, src.delete(src.find("sam")).size)
        assertEquals(listOf("unrelated.txt"), ws.list()!!.toList())
    }

    // ── §4 Subagents ──
    private class EchoRunner(val gate: CompletableDeferred<Unit>? = null, val fail: Boolean = false) : AgentRunner {
        override suspend fun run(task: String, inbox: ReceiveChannel<String>, ctx: ChildContext): String {
            ctx.report("started $task")
            gate?.await()
            if (fail) throw IllegalStateException("boom")
            val msgs = ArrayList<String>()
            while (true) { msgs += inbox.tryReceive().getOrNull() ?: break }
            return "$task done" + if (msgs.isEmpty()) "" else " with ${msgs.joinToString()}"
        }
    }

    @Test fun spawnSteerVerifyAndHandOff() = runBlocking {
        val got = CompletableDeferred<Handoff>()
        val gate = CompletableDeferred<Unit>()
        val o = SubagentOrchestrator(this + Dispatchers.Default, { got.complete(it) }, verify = { _, r -> r.contains("done") to "checked" })
        val id = o.spawn("research", EchoRunner(gate))
        assertTrue(o.send(id, "focus on prices"))
        assertEquals(ChildStatus.RUNNING, o.list().single().status)
        gate.complete(Unit)
        val h = withTimeout(5_000) { got.await() }
        assertEquals("research done with focus on prices", h.result)
        assertTrue(h.verified)
        assertEquals(ChildStatus.DONE, o.list().single().status)
    }

    @Test fun failuresHandOffAsFailedUnverified() = runBlocking {
        val got = CompletableDeferred<Handoff>()
        val o = SubagentOrchestrator(this + Dispatchers.Default, { got.complete(it) })
        o.spawn("x", EchoRunner(fail = true))
        val h = withTimeout(5_000) { got.await() }
        assertEquals(ChildStatus.FAILED, h.status)
        assertFalse(h.verified)
    }

    @Test fun interruptResumeAndClose() = runBlocking<Unit> {
        val handoffs = ArrayList<Handoff>()
        val gate = CompletableDeferred<Unit>()
        val o = SubagentOrchestrator(this + Dispatchers.Default, { synchronized(handoffs) { handoffs += it } })
        val id = o.spawn("long job", EchoRunner(gate))
        o.send(id, "note 1")
        assertTrue(o.interrupt(id))
        assertEquals(ChildStatus.INTERRUPTED, o.list().single().status)
        gate.complete(Unit)
        assertTrue(o.resume(id))
        withTimeout(5_000) { while (o.list().single().status != ChildStatus.DONE) delay(10) }
        assertEquals("long job done with note 1", handoffs.single().result)
        val id2 = o.spawn("other", EchoRunner(CompletableDeferred()))
        assertTrue(o.close(id2))
        assertFalse(o.send(id2, "late"))
        delay(50)
        assertEquals(1, handoffs.size) // a closed child hands nothing off
        coroutineContext.cancelChildren()
    }

    @Test fun childrenHaveNoWayToSpawn() {
        assertFalse(ChildContext::class.java.methods.any { it.name.contains("spawn", true) })
    }

    private fun kotlin.coroutines.CoroutineContext.cancelChildren() = this[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
}
