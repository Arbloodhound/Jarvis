package com.agent.jarvis.listen

import android.content.Context

/** [ConversationInsights] over what was really heard: the Insights screen, "how did I talk today",
 *  and the memory model's daily insights note. */
object Insights {
    fun report(ctx: Context, p: ConversationInsights.Period, now: Long = System.currentTimeMillis()): ConversationInsights.Report {
        val since = if (p == ConversationInsights.Period.TODAY)
            java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        else now - p.days * 86_400_000L
        val lines = AmbientMemory.get(ctx).taggedSince(since, 20_000).map { ConversationInsights.Line(it.ts, it.text) }
        return ConversationInsights.analyse(lines)
    }

    /** Once a day (from the memory model's run): yesterday's insights as a note, if there was talk. */
    fun dailyNote(ctx: Context) {
        val prefs = ctx.getSharedPreferences("ambient", Context.MODE_PRIVATE)
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        if (prefs.getString("insights_day", "") == yesterday.toString()) return
        val from = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val lines = AmbientMemory.get(ctx).taggedSince(from, 20_000).filter { it.ts < to }
            .map { ConversationInsights.Line(it.ts, it.text) }
        val r = ConversationInsights.analyse(lines, zone)
        if (r.words >= 200) MemoryNotes.get(ctx).addInsight(ConversationInsights.noteText(r, yesterday.toString()))
        prefs.edit().putString("insights_day", yesterday.toString()).apply()
    }
}
