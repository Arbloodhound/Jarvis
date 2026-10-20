package com.agent.jarvis.agent

import android.content.Context
import com.agent.jarvis.harness.CronJob
import com.agent.jarvis.harness.Feed
import com.agent.jarvis.harness.Ideas
import com.agent.jarvis.harness.Proactivity
import com.agent.jarvis.harness.SchedulerRuntime
import com.agent.jarvis.provider.OpenAiClient
import com.agent.jarvis.provider.ProviderConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The "For you" surfaces (HANDOFF_V5 §5), on Muse's harness modules: a feed written to the owner's
 * own prompt (Feed), ideas grounded in their notes (Ideas — no source, no idea), and proactive
 * suggestions that respect quiet hours, mutes and a daily budget (Proactivity). The generators are
 * cloud model calls; with no cloud brain or with offline-only on, nothing is generated and the
 * screen says why. Automatic refresh and notifications are off until the owner turns them on.
 */
object ForYou {
    fun feed(ctx: Context) = Feed(File(ctx.filesDir, "harness/feed.json"))
    fun ideas(ctx: Context) = Ideas(File(ctx.filesDir, "harness/ideas.json"))

    // --- Bandit ranking: ideas learn from what the owner acts on vs dismisses (no fine-tuning). ---
    private fun bandit(ctx: Context) =
        com.agent.jarvis.harness.BanditArmState(com.agent.jarvis.data.UltraDatabase.get(ctx).banditArms())

    /** How much the learned arm signal (fast mean, [-1,1]) nudges an idea's own [Ideas.Idea.score]. */
    private const val BANDIT_WEIGHT = 0.5

    /**
     * Open ideas, best first, with each idea's own rank nudged by its bandit arm's learned reward —
     * so topics the owner keeps acting on rise and ones they keep dismissing fall. Falls back to the
     * plain rank when nothing has been learned yet.
     */
    suspend fun rankedIdeas(ctx: Context, limit: Int = 10): List<Ideas.Idea> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val open = ideas(ctx).ranked(limit * 2)
            if (open.isEmpty()) return@withContext open
            val st = bandit(ctx)
            open.map { i ->
                val arm = st.get(com.agent.jarvis.harness.BanditFeedback.POLICY_IDEAS,
                    com.agent.jarvis.harness.IdeaArm.domain(i.title), com.agent.jarvis.harness.IdeaArm.lane(i.feasibility))
                val learned = arm?.let { com.agent.jarvis.harness.BanditScoring.fastMean(it) } ?: 0.0
                i to (i.score + BANDIT_WEIGHT * learned)
            }.sortedByDescending { it.second }.take(limit).map { it.first }
        }

    suspend fun onIdeaActed(ctx: Context, i: Ideas.Idea) = com.agent.jarvis.harness.BanditFeedback.onIdeaActed(
        bandit(ctx), com.agent.jarvis.harness.IdeaArm.domain(i.title), com.agent.jarvis.harness.IdeaArm.lane(i.feasibility))

    suspend fun onIdeaDismissed(ctx: Context, i: Ideas.Idea) = com.agent.jarvis.harness.BanditFeedback.onIdeaDismissed(
        bandit(ctx), com.agent.jarvis.harness.IdeaArm.domain(i.title), com.agent.jarvis.harness.IdeaArm.lane(i.feasibility))

    suspend fun onIdeaShown(ctx: Context, i: Ideas.Idea) = com.agent.jarvis.harness.BanditFeedback.onIdeaShown(
        bandit(ctx), com.agent.jarvis.harness.IdeaArm.domain(i.title), com.agent.jarvis.harness.IdeaArm.lane(i.feasibility))

    private const val PREFS = "for_you"
    private const val K_AUTO = "auto"
    private const val CHANNEL = "for_you"
    private const val PROACTIVE_JOB = "harness:proactive"

    fun auto(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_AUTO, false)

    /** Hourly feed + ideas refresh and proactive notifications: on/off (off by default). */
    fun setAuto(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_AUTO, on).apply()
        if (on) {
            SchedulerRuntime.schedule(ctx, Feed.job())
            SchedulerRuntime.schedule(ctx, CronJob(id = PROACTIVE_JOB, owner = "harness", task = "for you: ideas + suggestions", everySecs = 3 * 3600))
        } else {
            SchedulerRuntime.cancel(ctx, Feed.JOB_ID)
            SchedulerRuntime.cancel(ctx, PROACTIVE_JOB)
        }
    }

    /** App start: the handlers the scheduler runs when those jobs are due. */
    fun registerJobs(ctx: Context) {
        val app = ctx.applicationContext
        SchedulerRuntime.register(app, Feed.JOB_ID) { if (auto(app)) generateFeed(app) }
        SchedulerRuntime.register(app, PROACTIVE_JOB) { if (auto(app)) { generateIdeas(app); proactive(app) } }
    }

    private fun client(ctx: Context): OpenAiClient? {
        if (com.agent.jarvis.ui.UltraPrefs.offlineOnly(ctx)) return null
        return ProviderConfig.load(ctx).takeIf { it.isUsable }?.let { OpenAiClient(it) }
    }

    /** Why nothing can be generated, or null when it can. */
    fun unavailable(ctx: Context): String? = when {
        com.agent.jarvis.ui.UltraPrefs.offlineOnly(ctx) -> "Offline-only mode is on, so nothing new is written here."
        !ProviderConfig.load(ctx).isUsable -> "This needs the cloud brain (Settings → AI PROVIDER)."
        else -> null
    }

    /** Pure. The JSON array in a model reply (it may wrap it in prose or a code fence). */
    fun jsonArray(reply: String): JSONArray? {
        val a = reply.indexOf('['); val b = reply.lastIndexOf(']')
        if (a < 0 || b <= a) return null
        return try { JSONArray(reply.substring(a, b + 1)) } catch (_: Exception) { null }
    }

    suspend fun generateFeed(ctx: Context): String {
        val ai = client(ctx) ?: return unavailable(ctx) ?: "Not available."
        val f = feed(ctx)
        val made = f.generate(System.currentTimeMillis()) { prompt, recent ->
            // Grounded: the same web research web_search uses (clearnet only here), read first, so the
            // feed is today's pages with their sources, not the model's memory.
            val sources = try { WebResearchRun.run(prompt) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            val reply = ai.complete(listOf(
                OpenAiClient.ChatMessage("system", "You write a short personal feed. Reply with ONLY a JSON array of up to 5 " +
                    "objects {\"title\",\"body\",\"category\"}; body at most 3 sentences ending with (source: <domain>); " +
                    "category one word. Use ONLY the web results given; never invent news, dates or numbers. " +
                    "No web results → at most 2 items from what you know, each ending with (from model knowledge)."),
                OpenAiClient.ChatMessage("user", "The owner's feed prompt: $prompt\nDon't repeat these titles: ${recent.take(30).joinToString(" | ")}" +
                    "\n\nWeb results:\n" + (sources?.take(6000) ?: "(none — the search found nothing)")),
            ), 900, 0.4).getOrNull().orEmpty()
            val arr = jsonArray(reply) ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                val t = o.optString("title").trim()
                if (t.isEmpty()) null else Feed.Draft(t, o.optString("body").trim(), o.optString("category").trim().ifBlank { "general" })
            }
        }
        return "${made.size} new item${if (made.size == 1) "" else "s"}."
    }

    suspend fun generateIdeas(ctx: Context): String {
        val ai = client(ctx) ?: return unavailable(ctx) ?: "Not available."
        val notes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.agent.jarvis.listen.MemoryNotes.get(ctx).list(null, "", 40)
        }
        if (notes.isEmpty()) return "No notes yet, so no ideas: every idea has to come from something of yours."
        val list = notes.joinToString("\n") { "note:${it.id} — ${it.text.take(200)}" }
        val reply = ai.complete(listOf(
            OpenAiClient.ChatMessage("system", "From the owner's notes, suggest up to 4 ideas worth doing. Reply with ONLY a JSON " +
                "array of {\"title\",\"why\",\"sources\":[\"note:<id>\",...],\"feasibility\",\"fit\",\"novelty\"} (ratings 0 to 1). " +
                "Every idea must cite the notes it comes from. No purchases, no sending anything."),
            OpenAiClient.ChatMessage("user", list),
        ), 900, 0.5).getOrNull().orEmpty()
        val known = notes.map { "note:${it.id}" }.toSet()
        val arr = jsonArray(reply) ?: return "The model gave no ideas this time."
        val ideas = ideas(ctx)
        var added = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val src = o.optJSONArray("sources")?.let { s -> (0 until s.length()).map { s.optString(it) } }.orEmpty().filter { it in known }
            if (ideas.add(o.optString("title").trim(), o.optString("why").trim(), src,
                    o.optDouble("feasibility", -1.0), o.optDouble("fit", -1.0), o.optDouble("novelty", -1.0)) != null) added++
        }
        return "$added new idea${if (added == 1) "" else "s"}."
    }

    /** Proactivity over the open ideas: send what passes (quiet hours, mutes, budget), as a notification. */
    suspend fun proactive(ctx: Context) {
        val hist = loadHistory(ctx)
        val (qs, qe) = com.agent.jarvis.ui.UltraPrefs.quietHours(ctx)
        val cands = ideas(ctx).ranked(5).map {
            Proactivity.Candidate(it.id, it.title.lowercase().split(' ').firstOrNull().orEmpty(), it.title, Proactivity.Reason.NEW_OPPORTUNITY, "idea:${it.id}")
        }
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val sent = Proactivity.decide(cands, Proactivity.Prefs(quietStartHour = qs, quietEndHour = qe), hist, now, hour)
            .filter { it.send }.map { it.candidate }
        sent.forEach { notify(ctx, it.title) }
        if (sent.isNotEmpty()) saveHistory(ctx, Proactivity.record(hist, sent, now))
    }

    private fun notify(ctx: Context, title: String) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "For you", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(ctx, 0,
            android.content.Intent(ctx, com.agent.jarvis.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.star_on).setContentTitle("An idea for you").setContentText(title)
            .setContentIntent(open).setAutoCancel(true).build()
        try { nm.notify(title.hashCode(), n) } catch (_: SecurityException) { /* notifications not allowed */ }
    }

    private fun historyFile(ctx: Context) = File(ctx.filesDir, "harness/proactive.json")

    private fun loadHistory(ctx: Context): Proactivity.History = try {
        val o = JSONObject(historyFile(ctx).readText())
        fun map(k: String) = o.optJSONObject(k)?.let { m -> m.keys().asSequence().associateWith { m.getLong(it) } }.orEmpty()
        // The daily budget counts today only.
        val today = java.time.LocalDate.now().toString()
        Proactivity.History(map("sent"), map("topics"), if (o.optString("day") == today) o.optInt("today") else 0)
    } catch (_: Exception) { Proactivity.History() }

    private fun saveHistory(ctx: Context, h: Proactivity.History) {
        historyFile(ctx).parentFile?.mkdirs()
        historyFile(ctx).writeText(JSONObject().put("sent", JSONObject(h.sentFingerprints)).put("topics", JSONObject(h.lastByTopic))
            .put("today", h.sentToday).put("day", java.time.LocalDate.now().toString()).toString())
    }
}
