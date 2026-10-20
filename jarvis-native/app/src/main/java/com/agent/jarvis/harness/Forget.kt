package com.agent.jarvis.harness

import java.io.File

/**
 * §3 — "forget X", with confirmation.
 *
 *  1. Plan — every [ForgetSource] lists what it holds about the subject (memory claims, notes,
 *     workspace files, drafts…), plus what it can't guarantee ([ForgetSource.limit]).
 *  2. Show the plan — [ForgetPlan.summary], plain language, scope and limits.
 *  3. The user confirms — only an [ForgetConfirmation] minted by the app from the user's tap runs it,
 *     for that exact plan, within [PLAN_TTL_MS]. No silent partial cleanup: nothing is deleted
 *     before the confirmation.
 *  4. Execute and verify — delete, then search every source again and report what's gone and what
 *     (if anything) is still there or couldn't be removed.
 *
 * Deletion here is a real delete (not the trash): forgetting into a recoverable trash would keep it.
 * Memory deletion is best-effort across stores, and the report says so. Pure; JVM-tested.
 */
interface ForgetSource {
    val name: String
    suspend fun find(subject: String): List<ForgetItem>
    /** Returns the items actually deleted. */
    suspend fun delete(items: List<ForgetItem>): List<ForgetItem>
    /** What this source can't promise (copies elsewhere, backups), or null. */
    val limit: String? get() = null
}

data class ForgetItem(val source: String, val id: String, val preview: String)

data class ForgetPlan(val id: String, val subject: String, val items: List<ForgetItem>,
                      val limits: List<String>, val createdAtMs: Long) {
    fun summary(): String = buildString {
        if (items.isEmpty()) {
            append("I found nothing saved about \"$subject\".")
        } else {
            append("To forget \"$subject\" I would permanently delete ${items.size} item(s):\n")
            items.groupBy { it.source }.forEach { (src, its) ->
                append("- $src: ${its.size} — ").append(its.take(3).joinToString("; ") { "\"${it.preview.take(60)}\"" })
                if (its.size > 3) append("; …")
                append('\n')
            }
            append("This can't be undone. Nothing is deleted until you confirm.")
        }
        if (limits.isNotEmpty()) append("\nLimits: ").append(limits.joinToString(" "))
    }
}

data class ForgetReport(val removed: List<ForgetItem>, val failed: List<ForgetItem>, val stillFound: List<ForgetItem>) {
    fun summary(): String = buildString {
        append("Removed ${removed.size} item(s).")
        if (failed.isNotEmpty()) append(" Couldn't remove ${failed.size}: ").append(failed.joinToString("; ") { "${it.source} ${it.id}" }).append('.')
        if (stillFound.isEmpty()) append(" A fresh search finds nothing left.")
        else append(" A fresh search still finds ${stillFound.size}: ").append(stillFound.joinToString("; ") { "${it.source}: \"${it.preview.take(40)}\"" }).append('.')
        append(" Memory deletion is best-effort; copies outside these stores (backups, messages you sent) aren't touched.")
    }
}

/** Minted by the app from the user's tap on the plan; the agent can't produce one. */
class ForgetConfirmation internal constructor(val planId: String)

class Forget(private val sources: List<ForgetSource>) {
    private val plans = java.util.concurrent.ConcurrentHashMap<String, ForgetPlan>()

    suspend fun plan(subject: String, nowMs: Long = System.currentTimeMillis()): ForgetPlan {
        require(subject.isNotBlank()) { "nothing to forget" }
        val items = sources.flatMap { s -> runCatching { s.find(subject) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() } }
        val limits = sources.mapNotNull { it.limit }
        val p = ForgetPlan("forget:" + java.util.UUID.randomUUID().toString().take(10), subject, items, limits, nowMs)
        plans[p.id] = p
        return p
    }

    /** The app calls this when the user taps "Forget" on [planId]'s plan. */
    fun userConfirmed(planId: String) = ForgetConfirmation(planId)

    suspend fun execute(confirmation: ForgetConfirmation, nowMs: Long = System.currentTimeMillis()): ForgetReport {
        val plan = plans.remove(confirmation.planId) ?: throw IllegalStateException("no such plan (already run or never made)")
        check(nowMs - plan.createdAtMs <= PLAN_TTL_MS) { "this plan is older than 10 minutes; make a new one" }
        val removed = ArrayList<ForgetItem>()
        for (s in sources) {
            val mine = plan.items.filter { it.source == s.name }
            if (mine.isEmpty()) continue
            removed += runCatching { s.delete(mine) }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() }
        }
        val failed = plan.items.filter { it !in removed }
        val still = sources.flatMap { s -> runCatching { s.find(plan.subject) }.getOrDefault(emptyList()) }
        return ForgetReport(removed, failed, still)
    }

    companion object { const val PLAN_TTL_MS = 10 * 60_000L }
}

/** Case-insensitive whole-phrase match, shared by the sources. */
internal fun mentions(text: String, subject: String) = text.lowercase().contains(subject.trim().lowercase())

/** Memory claims (harness/MemoryClaims.kt), through [ClaimForgetDao]. Superseded history counts too. */
class ClaimsForgetSource(private val dao: ClaimForgetDao) : ForgetSource {
    override val name = "memory claims"
    override suspend fun find(subject: String) = dao.all()
        .filter { mentions(it.text + " " + it.quote, subject) }
        .map { ForgetItem(name, it.id, it.text) }
    override suspend fun delete(items: List<ForgetItem>) = items.filter { dao.delete(it.id) > 0 }
}

/** The notes store, via plain functions so it is testable (MemoryNotes.list / MemoryNotes.delete on the phone). */
class NotesForgetSource(
    private val listMatching: suspend (String) -> List<Pair<Long, String>>,
    private val deleteNote: suspend (Long) -> Unit,
) : ForgetSource {
    override val name = "notes"
    override val limit = "Words heard by ambient listening are kept in a separate store this doesn't search."
    override suspend fun find(subject: String) = listMatching(subject)
        .filter { mentions(it.second, subject) }
        .map { ForgetItem(name, it.first.toString(), it.second) }
    override suspend fun delete(items: List<ForgetItem>) = items.filter { i ->
        val id = i.id.toLongOrNull() ?: return@filter false
        runCatching { deleteNote(id) }.isSuccess
    }
}

/** Text files under the workspace (drafts, artifacts, daily notes) whose name or content mentions the subject. */
class FilesForgetSource(private val roots: List<File>, private val maxBytes: Long = 1_000_000) : ForgetSource {
    override val name = "files"
    override suspend fun find(subject: String) = roots.filter { it.isDirectory }.flatMap { root ->
        root.walkTopDown().filter { it.isFile }.filter { f ->
            mentions(f.name, subject) || (f.length() <= maxBytes && runCatching { mentions(f.readText(), subject) }.getOrDefault(false))
        }.map { ForgetItem(name, it.path, it.name) }.toList()
    }
    override suspend fun delete(items: List<ForgetItem>) = items.filter { File(it.id).delete() }
}
