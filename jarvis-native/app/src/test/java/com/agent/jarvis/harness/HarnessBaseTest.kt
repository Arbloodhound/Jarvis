package com.agent.jarvis.harness

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The six base harness files and the scheduler's durable book (JobBook). */
class HarnessBaseTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = 86_400_000L

    // ── MemoryClaimScoring ──
    private fun claim(text: String, conf: Double = 0.7, ageDays: Double = 0.0, evergreen: Boolean = false, now: Long = 100 * day) =
        ClaimEntity("c", text, "fact", "medium", conf, "user", "m", reinforcedAt = now - (ageDays * day).toLong(), evergreen = evergreen)

    @Test fun temporalFactorHalvesEveryThirtyDaysUnlessEvergreen() {
        val now = 100 * day
        assertEquals(1.0, MemoryClaimScoring.temporalFactor(claim("x", now = now), now), 1e-9)
        assertEquals(0.5, MemoryClaimScoring.temporalFactor(claim("x", ageDays = 30.0, now = now), now), 1e-6)
        assertEquals(1.0, MemoryClaimScoring.temporalFactor(claim("x", ageDays = 300.0, evergreen = true, now = now), now), 1e-9)
    }

    @Test fun scoreIsZeroWithoutAKeywordHitAndBlendsConfidence() {
        val now = 100 * day
        assertEquals(0.0, MemoryClaimScoring.score("bicycle", claim("likes tea", now = now), now), 0.0)
        val s = MemoryClaimScoring.score("tea morning", claim("likes tea in the morning", conf = 1.0, now = now), now)
        assertEquals(0.7 * 1.0 + 0.3 * 1.0, s, 1e-9)
    }

    // ── Scheduler / CronJob ──
    @Test(expected = IllegalArgumentException::class) fun aCronJobNeedsAnOwner() { CronJob("x", "t", owner = "") }

    @Test fun dueRulesPerKind() {
        assertTrue(CronJob("a", "t", "harness", everySecs = 60, lastRunMs = 0).due(60_000))
        assertFalse(CronJob("a", "t", "harness", everySecs = 60, lastRunMs = 10_000).due(60_000))
        assertTrue(CronJob("b", "t", "harness", kind = "runonce", atMs = 500).due(500))
        assertFalse(CronJob("b", "t", "harness", kind = "runonce", atMs = 500, lastRunMs = 600).due(700))
        assertFalse(CronJob("c", "t", "harness", enabled = false).due(Long.MAX_VALUE))
    }

    @Test fun hooksStartDisabledAndAThrowingConditionStaysSilent() {
        val s = Scheduler()
        s.addHook(Hook("h", "t", pollSecs = 1, condition = { true to mapOf("k" to "v") }))
        s.addHook(Hook("bad", "t", pollSecs = 1, condition = { throw IllegalStateException() }, enabled = true))
        assertEquals(emptyList<Any>(), s.pollHooks(10_000))
        s.enableHook("h")
        assertEquals(listOf("h"), s.pollHooks(20_000).map { it.first })
    }

    // ── JobBook (durable scheduler + firing decision) ──
    @Test fun jobBookRunsDueJobsMarksThemAndPicksTheNextWake() = runBlocking {
        val book = JobBook(File(tmp.root, "jobs.json"))
        book.put(CronJob("harness:feed", "feed", "harness", everySecs = 3600, lastRunMs = 0))
        book.put(CronJob("goal:x:remind", "remind", "goal:x", kind = "runonce", atMs = 5_000))
        book.put(CronJob("orphan", "nobody handles me", "item:y", everySecs = 60))
        val ran = ArrayList<String>()
        val record: suspend (CronJob) -> Unit = { j -> ran += j.id }
        val handlers: Map<String, suspend (CronJob) -> Unit> = mapOf("harness:feed" to record, "goal:" to record)
        val (outcomes, next) = book.tick(3_600_000, handlers)
        assertEquals(setOf("harness:feed", "goal:x:remind"), ran.toSet())
        assertEquals("no handler", outcomes.first { it.jobId == "orphan" }.result)
        val after = book.load().associateBy { it.id }
        assertFalse("runonce never fires twice", after.getValue("goal:x:remind").enabled)
        assertEquals(3_600_000L, after.getValue("orphan").lastRunMs)
        assertEquals(3_600_000L + 60_000, next) // the orphan's next period is the earliest
    }

    @Test fun aThrowingHandlerIsReportedAndStillMarkedRun() = runBlocking {
        val book = JobBook(File(tmp.root, "jobs.json"))
        book.put(CronJob("j", "t", "harness", everySecs = 60))
        val boom: suspend (CronJob) -> Unit = { throw IllegalStateException("x") }
        val handlers: Map<String, suspend (CronJob) -> Unit> = mapOf("j" to boom)
        val (out, _) = book.tick(60_000, handlers)
        assertEquals("failed: IllegalStateException", out.single().result)
        assertEquals(60_000L, book.load().single().lastRunMs)
    }

    @Test fun malformedJobsAreSkippedNotGuessed() {
        val f = File(tmp.root, "jobs.json")
        f.writeText("""[{"id":"ok","owner":"harness","kind":"daily"},{"id":"noowner","owner":""},{"id":"badkind","owner":"h","kind":"weekly"}]""")
        assertEquals(listOf("ok"), JobBook(f).load().map { it.id })
        assertNull(JobBook.nextWake(emptyList(), 0))
    }

    // ── SkillStore ──
    @Test fun skillsDiscoverFirstRootWinsDirectoryIsMetadataOnlyAndAllowlistApplies() {
        fun skill(root: File, dir: String, name: String, desc: String, body: String = "BODY") =
            File(root, "$dir/SKILL.md").apply { parentFile.mkdirs(); writeText("---\nname: $name\ndescription: $desc\n---\n$body") }
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        skill(a, "shop", "shopping", "find products")
        skill(b, "shop2", "shopping", "a second copy")
        skill(b, "trip", "trip-planning", "plan a trip", body = "SECRET BODY")
        val s = SkillStore()
        assertEquals(2, s.discover(listOf(a, b)))
        assertTrue(s.directory().contains("find products"))
        assertFalse("bodies never go in the directory", s.directory().contains("SECRET BODY"))
        assertEquals("trip-planning", s.search("plan trip").first())
        assertTrue(s.read("trip-planning")!!.contains("SECRET BODY"))
        val limited = SkillStore(allowlist = setOf("shopping")).apply { discover(listOf(a, b)) }
        assertNull(limited.read("trip-planning"))
    }

    // ── NotificationForwardingPolicy ──
    private fun at(hour: Int, minute: Int = 0) = java.util.Calendar.getInstance().apply {
        set(2026, 9, 15, hour, minute, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun notificationGatesOwnPackageAllowlistQuietHoursAndBurst() {
        val p = NotificationForwardingPolicy(allowedPackages = setOf("chat.app"), ownPackage = "com.agent.jarvis", maxPerMinutePerApp = 2)
        assertFalse(p.shouldForward(IncomingNotification("com.agent.jarvis", "t", postedAtMs = at(12))))
        assertFalse(p.shouldForward(IncomingNotification("other.app", "t", postedAtMs = at(12))))
        assertFalse("quiet hours", p.shouldForward(IncomingNotification("chat.app", "t", postedAtMs = at(23))))
        assertTrue(p.shouldForward(IncomingNotification("chat.app", "1", postedAtMs = at(12))))
        assertTrue(p.shouldForward(IncomingNotification("chat.app", "2", postedAtMs = at(12))))
        assertFalse("burst limit", p.shouldForward(IncomingNotification("chat.app", "3", postedAtMs = at(12))))
        assertTrue("next minute", p.shouldForward(IncomingNotification("chat.app", "4", postedAtMs = at(12, 1))))
    }

    // ── ScreenGuard ──
    @Test fun screenGuardRefusesStaleSnapshotsAndPasswordFields() {
        val g = ScreenGuard()
        val id = g.observe(uiEpoch = 3, foregroundPackage = "shop.app")
        g.preflight(id, 3, "shop.app") // fresh: passes
        assertTrue(runCatching { g.preflight(id, 3, "other.app") }.exceptionOrNull() is StaleTarget)
        val id2 = g.observe(4, "shop.app")
        assertTrue(runCatching { g.preflight(id, 4, "shop.app") }.exceptionOrNull() is StaleTarget)
        assertTrue(runCatching { g.preflight(id2, 5, "shop.app") }.exceptionOrNull() is StaleTarget)
        assertTrue(PasswordField.isPassword(0x00000081, false)) // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD
        assertTrue(PasswordField.isPassword(0x000000e1, false)) // web password
        assertTrue(PasswordField.isPassword(0x00000001, true))  // node password flag
        assertFalse(PasswordField.isPassword(0x00000001, false))
        assertTrue(runCatching { g.requireNotPassword(0x00000081, false) }.exceptionOrNull() is SecureContentRefused)
    }
}
