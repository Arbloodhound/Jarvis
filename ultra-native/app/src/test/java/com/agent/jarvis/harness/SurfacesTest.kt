package com.agent.jarvis.harness

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** §5: Feed, Ideas, Artifacts, Proactivity. */
class SurfacesTest {
    @get:Rule val tmp = TemporaryFolder()

    // ── Feed ──
    @Test fun feedRecordsTheSourcePromptKeepsUserOrderAndSkipsRepeats() = runBlocking {
        val f = Feed(File(tmp.root, "feed.json"))
        f.setPrompt("Only cycling news")
        val first = f.generate(1) { p, _ -> listOf(Feed.Draft("Tour route announced", "…", "cycling"), Feed.Draft("New bike lane", "…", "local")) }
        assertEquals(listOf("Only cycling news", "Only cycling news"), first.map { it.sourcePrompt })
        f.reorder(first[1].id, 0)
        val second = f.generate(2) { _, recent ->
            assertTrue(recent.contains("Tour route announced"))
            listOf(Feed.Draft("Tour Route Announced!", "dup", "cycling"), Feed.Draft("Race results", "…", "cycling"))
        }
        assertEquals(listOf("Race results"), second.map { it.title })
        assertEquals(listOf("Race results", "New bike lane", "Tour route announced"), f.units().map { it.title })
        // persisted
        val again = Feed(File(tmp.root, "feed.json"))
        assertEquals("Only cycling news", again.prompt())
        assertEquals(3, again.units().size)
    }

    @Test fun feedEngagementAndUserRemoval() = runBlocking {
        val f = Feed(File(tmp.root, "feed.json"))
        val u = f.generate(1) { _, _ -> listOf(Feed.Draft("A", "", "x"), Feed.Draft("B", "", "y")) }
        f.engage(u[0].id, "like"); f.engage(u[0].id, "open"); f.engage(u[1].id, "dismiss")
        assertEquals(listOf("A"), f.units().map { it.title })
        assertTrue(f.categoryInterest().getValue("x") > f.categoryInterest().getValue("y"))
        assertTrue(f.removeByUser(u[0].id))
        f.resetPrompt(); assertEquals(Feed.DEFAULT_PROMPT, f.prompt())
        assertEquals("harness", Feed.job().owner)
    }

    // ── Ideas ──
    @Test fun ideasNeedASourceRankByTheLabelledRuleAndDoNotNag() {
        val i = Ideas(File(tmp.root, "ideas.json"))
        assertNull("no source, no idea", i.add("Learn Spanish", "why", emptyList(), 0.5, 0.5, 0.5))
        assertNull("out of range", i.add("X", "why", listOf("note:1"), 1.5, 0.5, 0.5))
        val a = i.add("Batch-cook Sunday lunches", "you cook every Sunday", listOf("note:12"), 0.9, 0.9, 0.2)!!
        val b = i.add("Try a climbing gym", "you mentioned climbing", listOf("note:3"), 0.6, 0.7, 0.9)!!
        assertEquals(listOf(a.id, b.id), i.ranked().map { it.id })
        assertTrue(i.dismiss(b.id, "not now"))
        assertNull("a dismissed idea doesn't come back reworded", i.add("Try the climbing gym", "again", listOf("note:9"), 1.0, 1.0, 1.0))
        assertNotNull("one dismissal doesn't mute the topic", i.add("Outdoor bouldering trip with friends", "new", listOf("note:9"), 0.5, 0.5, 0.5))
        assertTrue(i.act(a.id))
        assertFalse(i.act(a.id))
        assertEquals(2, Ideas(File(tmp.root, "ideas.json")).history().size)
    }

    // ── Artifacts ──
    @Test fun artifactsVersionShareOnlyWithConsentAndDeleteToTrash() {
        val ws = tmp.newFolder("ws")
        val trash = Trash(File(tmp.root, "trash"), listOf(ws))
        val arts = Artifacts(File(ws, "artifacts"), trash)
        val a = arts.create("Trip plan: Lisbon", Artifacts.Kind.DOCUMENT, "v1 text", nowMs = 1)
        assertEquals("trip-plan-lisbon", a.slug)
        arts.edit(a.slug, "v2 text", nowMs = 2)
        assertEquals("v2 text", arts.content(a.slug))
        assertEquals("v1 text", arts.content(a.slug, 1))
        assertEquals("trip-plan-lisbon-2", arts.create("Trip plan: Lisbon", Artifacts.Kind.DOCUMENT, "x").slug)
        assertFalse(arts.get(a.slug)!!.shared)
        assertTrue(arts.share(arts.userAskedToShare(a.slug)).shared)
        val tid = arts.delete(a.slug)
        assertNull(arts.get(a.slug))
        trash.restore(tid)
        assertEquals("v2 text", arts.content(a.slug))
    }

    // ── Proactivity ──
    private fun c(id: String, topic: String, reason: Proactivity.Reason, fp: String = id, confirmed: Boolean = true) =
        Proactivity.Candidate(id, topic, "t $id", reason, fp, confirmed)

    @Test fun proactivityNeverRepeatsRespectsMutesQuietHoursAndBudget() {
        val prefs = Proactivity.Prefs(mutedTopics = setOf("sports"), maxPerDay = 2, topicGapHours = 6)
        val hist = Proactivity.History(sentFingerprints = mapOf("old" to 0L))
        val cands = listOf(
            c("a", "travel", Proactivity.Reason.NEW_OPPORTUNITY),
            c("b", "bills", Proactivity.Reason.TIME_SENSITIVE),
            c("c", "sports", Proactivity.Reason.CHANGED),
            c("d", "travel", Proactivity.Reason.CHANGED, fp = "old"),
            c("e", "health", Proactivity.Reason.READY_FOR_DECISION, confirmed = false),
            c("f", "garden", Proactivity.Reason.NEW_OPPORTUNITY),
            c("g", "garden", Proactivity.Reason.REQUESTED))
        val d = Proactivity.decide(cands, prefs, hist, nowMs = 10 * 3_600_000L, hourOfDay = 14).associateBy { it.candidate.id }
        assertTrue(d.getValue("b").send)
        assertEquals(Proactivity.Skip.PREFERENCES, d.getValue("c").skip)
        assertEquals(Proactivity.Skip.ALREADY_HANDLED, d.getValue("d").skip)
        assertEquals(Proactivity.Skip.UNCONFIRMED, d.getValue("e").skip)
        assertTrue("requested always goes", d.getValue("g").send)
        assertEquals("budget of 2 non-requested sends", 2, d.values.count { it.send && it.candidate.reason != Proactivity.Reason.REQUESTED })
        // Quiet hours hold all but time-sensitive and requested.
        val night = Proactivity.decide(cands, prefs, Proactivity.History(), nowMs = 0, hourOfDay = 23).filter { it.send }.map { it.candidate.id }.toSet()
        assertEquals(setOf("b", "g"), night)
    }

    @Test fun proactivityHistoryBlocksTheSameTopicForAWhile() {
        val prefs = Proactivity.Prefs(maxPerDay = 5, topicGapHours = 6)
        val sent = listOf(c("a", "travel", Proactivity.Reason.CHANGED))
        val h = Proactivity.record(Proactivity.History(), sent, nowMs = 0)
        val later = Proactivity.decide(listOf(c("b", "travel", Proactivity.Reason.CHANGED)), prefs, h, nowMs = 3_600_000, hourOfDay = 12)
        assertEquals(Proactivity.Skip.NOT_USEFUL_NOW, later.single().skip)
        val muchLater = Proactivity.decide(listOf(c("b", "travel", Proactivity.Reason.CHANGED)), prefs, h, nowMs = 7 * 3_600_000L, hourOfDay = 12)
        assertTrue(muchLater.single().send)
    }
}
