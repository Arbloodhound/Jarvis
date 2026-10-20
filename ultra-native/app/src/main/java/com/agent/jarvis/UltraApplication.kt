package com.agent.jarvis

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat

class UltraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Route and action-gate entries in the audit log need a Context they are not handed.
        com.agent.jarvis.gate.GateAuditLog.init(this)
        // Thermal headroom polling (API 30+; no-op below). Read via ThermalGuard.level.
        try { com.agent.jarvis.agent.ThermalGuard.start(this) } catch (_: Exception) {}
        // Pre-tick apps that look like they hold money or credentials, once,
        // before the agent has a chance to read anything.
        try { com.agent.jarvis.ui.ProtectedApps.seedIfUnset(this) } catch (_: Exception) {}
        wireMemory()
        // Harness scheduler: arm the next tick for the jobs already scheduled (harness/SchedulerRuntime.kt).
        try { com.agent.jarvis.harness.SchedulerRuntime.start(this) } catch (_: Exception) {}
        try { com.agent.jarvis.agent.ForYou.registerJobs(this) } catch (_: Exception) {}
        // Foreground service keeps the process alive when backgrounded —
        // on this device class (3.5GB Samsung) backgrounded processes lose
        // their accessibility binding and get reclaimed.
        // Not while the owner has Jarvis switched off (JarvisPower).
        if (JarvisPower.isOn(this)) try {
            ContextCompat.startForegroundService(
                this, Intent(this, AgentBackgroundService::class.java)
            )
        } catch (e: Exception) {
            android.util.Log.w("UltraApp", "background service start failed", e)
        }
    }

    /** Memory search covers the librarian's notes too, and the offline brain gets its brief from
     * it. MemoryBrief adds its own header, so the recall block's header line is dropped here. */
    private fun wireMemory() {
        val ctx = applicationContext
        // Notes the old note tool left as text files join the one notes store (once).
        Thread {
            runCatching { com.agent.jarvis.listen.MemoryNotes.get(ctx).importLegacyFiles(ctx.filesDir) }
                .onSuccess { if (it > 0) android.util.Log.i("UltraMemory", "imported $it old note file(s)") }
        }.start()
        com.agent.jarvis.listen.MemoryRecall.extraSources +=
            com.agent.jarvis.listen.MemoryRecall.textSource("notes") { q, n ->
                com.agent.jarvis.listen.MemoryNotes.get(ctx).search(q, n).map { it.ts to it.text }
            }
        // Verified durable memory (the claim store: facts/people promoted through the verify + NLI
        // gate). active() only, so a superseded claim never resurfaces next to its replacement.
        com.agent.jarvis.listen.MemoryRecall.extraSources +=
            com.agent.jarvis.listen.MemoryRecall.textSource("memory") { q, n ->
                com.agent.jarvis.harness.MemoryClaims(com.agent.jarvis.data.UltraDatabase.get(ctx).claims())
                    .searchScored(q).take(n).map { (c, _) -> c.reinforcedAt to c.text }
            }
        com.agent.jarvis.agent.MemoryBrief.provider = { q, now ->
            val r = com.agent.jarvis.listen.MemoryRecall
            r.brief(r.relevant(ctx, q, now)).substringAfter('\n', "").ifBlank { null }
        }
        // The memory / notes tools (MemoryFocus): what the focused question reasons over.
        com.agent.jarvis.agent.MemoryFocus.provider = { ask, now -> focusedFind(ctx, ask, now) }
        // "What do you remember?": the newest notes and heard lines (notes first under the char cap).
        com.agent.jarvis.agent.MemoryBrief.overviewProvider = { _ ->
            val notes = com.agent.jarvis.listen.MemoryNotes.get(ctx).search("", 6)
                .map { com.agent.jarvis.listen.RecallHit(it.ts, it.text, 1f, "note") }
            val heard = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.agent.jarvis.listen.AmbientMemory.get(ctx).newest(6)
            }.map { com.agent.jarvis.listen.RecallHit(it.ts, it.text, 0.5f, "heard") }
            com.agent.jarvis.listen.MemoryRecall.brief(notes + heard).substringAfter('\n', "").ifBlank { null }
        }
    }

    /** Lines for a focused question: notes as "[todo, Tue 7 Oct 2026] text"; heard lines with
     *  their time. The newest when the query finds nothing (or is empty). */
    private suspend fun focusedFind(ctx: android.content.Context, ask: com.agent.jarvis.agent.MemoryFocus.Ask, now: Long): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val zone = java.time.ZoneId.systemDefault()
            val parts = ArrayList<String>()
            if (ask.focus == com.agent.jarvis.agent.MemoryFocus.Focus.NOTES || ask.focus == com.agent.jarvis.agent.MemoryFocus.Focus.BOTH) {
                val store = com.agent.jarvis.listen.MemoryNotes.get(ctx)
                val kind = if (ask.todosOnly) com.agent.jarvis.listen.NoteKind.TODO else null
                val notes = store.list(kind, ask.query, 15).ifEmpty { store.list(kind, "", 15) }
                if (notes.isNotEmpty()) parts += "Notes:\n" + notes.joinToString("\n") { "- " + it.describe(zone) }
            }
            if (ask.focus == com.agent.jarvis.agent.MemoryFocus.Focus.MEMORY || ask.focus == com.agent.jarvis.agent.MemoryFocus.Focus.BOTH) {
                val r = com.agent.jarvis.listen.MemoryRecall
                val hits = (if (ask.query.isBlank()) emptyList() else r.relevant(ctx, ask.query, now, 12))
                    .filter { it.source == r.SOURCE_HEARD }
                    .ifEmpty {
                        com.agent.jarvis.listen.AmbientMemory.get(ctx).newest(12)
                            .map { com.agent.jarvis.listen.RecallHit(it.ts, it.text, 0.5f, r.SOURCE_HEARD) }
                    }
                val fmt = java.text.SimpleDateFormat("EEE d MMM h:mm a", java.util.Locale.UK)
                if (hits.isNotEmpty()) parts += "Heard:\n" + hits.sortedBy { it.ts }
                    .joinToString("\n") { "- ${fmt.format(java.util.Date(it.ts))}: ${it.text}" }
            }
            parts.joinToString("\n").ifBlank { null }
        }

    companion object {
        lateinit var instance: UltraApplication
            private set
    }
}
