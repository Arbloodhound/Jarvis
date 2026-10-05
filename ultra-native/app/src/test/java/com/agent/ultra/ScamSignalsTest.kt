package com.agent.ultra

import com.agent.ultra.agent.EventTrigger
import com.agent.ultra.agent.ScamSignals
import com.agent.ultra.agent.ScamSignals.Sign
import com.agent.ultra.gate.ScamWatch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The scam detector against a hand-written corpus (test/resources/scam_corpus.json):
 * no ordinary message may be flagged, and the scam hit rate is printed, not assumed.
 */
class ScamSignalsTest {

    @Before fun reset() = ScamWatch.clear()

    private fun corpus(kind: String): List<Pair<String, String>> {
        val arr = JSONObject(javaClass.classLoader!!.getResource("scam_corpus.json")!!.readText()).getJSONArray(kind)
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("from") to o.getString("text") } }
    }

    @Test fun noOrdinaryMessageIsFlagged() {
        val flagged = corpus("benign").map { (from, text) -> text to ScamSignals.assess(from, text) }.filter { it.second.scam }
        assertTrue("false alarms:\n" + flagged.joinToString("\n") { "${it.first}  <- ${it.second.reasons}" }, flagged.isEmpty())
    }

    @Test fun mostScamsAreFlagged() {
        val all = corpus("scam")
        val missed = all.filter { (from, text) -> !ScamSignals.assess(from, text).scam }
        println("scam corpus: ${all.size - missed.size}/${all.size} flagged; missed:\n" + missed.joinToString("\n") { it.second })
        assertTrue("missed ${missed.size}/${all.size}:\n" + missed.joinToString("\n") { it.second }, missed.size * 10 <= all.size)
    }

    @Test fun reasonsArePlainAndAskFirst() {
        val a = ScamSignals.assess("+1 555", "Buy Google Play cards and send me pictures of the codes. Don't tell anyone.")
        assertTrue(a.scam)
        assertEquals(Sign.GIFT_CARDS, a.signs.first())
        assertTrue(a.reasons, a.reasons.startsWith("asks for gift cards"))
        assertTrue(Sign.SECRECY in a.signs)
    }

    @Test fun otpWarningIsNotACodeRequest() {
        val a = ScamSignals.assess("Bank", "Your verification code is 552190. Do not share this code with anyone.")
        assertFalse(Sign.CODE_REQUEST in a.signs)
        assertFalse(a.scam)
        val b = ScamSignals.assess("Unknown", "Please send me the verification code you just received")
        assertTrue(Sign.CODE_REQUEST in b.signs)
        assertTrue(b.scam)
    }

    @Test fun targetsAreNormalised() {
        val a = ScamSignals.assess("x", "IRS: warrant for your arrest. Call +1 (888) 555-0199 now or visit https://www.irs-pay-now.top/case?id=9")
        assertTrue(a.scam)
        assertTrue(a.targets.toString(), "phone:8885550199" in a.targets)
        assertTrue(a.targets.toString(), "host:irs-pay-now.top" in a.targets)
    }

    @Test fun datesAndPricesAreNotPhones() {
        assertTrue(ScamSignals.targetsIn("due 2026-09-19, pay $1,299.99").none { it.startsWith("phone:") })
        assertEquals(setOf("phone:5550109999"), ScamSignals.targetsIn("text 1-555-010-9999").filter { it.startsWith("phone:") }.toSet())
    }

    @Test fun notHostsAreNotLinks() {
        assertTrue(ScamSignals.targetsIn("Mr.Smith paid 3.50 for file.txt").none { it.startsWith("host:") })
        assertTrue("host:evil.top" in ScamSignals.targetsIn("go to evil.top now"))
    }

    // ── trigger → ScamWatch ──────────────────────────────────────────

    @Test fun triggerWarnsAndRemembersTargets() {
        val a = EventTrigger.ScamTrigger.check("com.google.android.apps.messaging", "+1 555 404 2222",
            "Your account is suspended. Verify your account at acct-restore.top within 24 hours.")
        assertNotNull(a)
        assertEquals(EventTrigger.Action.Type.WARN, a!!.type)
        assertTrue(a.label.startsWith("This message looks like a scam:"))
        assertNotNull(ScamWatch.match("https://acct-restore.top/login"))
    }

    @Test fun triggerIgnoresOrdinaryAndOwnMessages() {
        assertNull(EventTrigger.ScamTrigger.check("com.google.android.apps.messaging", "Dad", "Can you pick up milk?"))
        assertNull(EventTrigger.ScamTrigger.check("com.agent.ultra", "Ultra", "Buy gift cards and send me the codes"))
        assertTrue(ScamWatch.recent().isEmpty())
    }

    @Test fun watchForgetsAfterTtl() {
        val a = ScamSignals.assess("x", "Grandpa I'm in jail, wire bail money via Western Union to 555-222-3333, don't tell mom")
        ScamWatch.remember(a, "x", nowMs = 0)
        assertNotNull(ScamWatch.match("555 222 3333", nowMs = 1000))
        assertNull(ScamWatch.match("555 222 3333", nowMs = ScamWatch.TTL_MS + 1))
    }
}

/**
 * Independent check against real messages this detector was never tuned on: the UCI SMS
 * Spam Collection (Almeida & Hidalgo, CC BY 4.0; 4,827 ordinary "ham" texts, 747 spam).
 * Not vendored — set ULTRA_SMS_CORPUS to the unzipped `SMSSpamCollection` file to run it;
 * skipped otherwise. Prints the false-alarm count and every false alarm.
 */
class ScamSignalsExternalTest {
    @Test fun uciHamFalseAlarms() {
        val path = System.getenv("ULTRA_SMS_CORPUS") ?: return
        val rows = java.io.File(path).readLines().mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 } }
        val ham = rows.filter { it[0] == "ham" }.map { it[1] }
        val spam = rows.filter { it[0] == "spam" }.map { it[1] }
        val fa = ham.filter { ScamSignals.assess("", it).scam }
        val sp = spam.count { ScamSignals.assess("", it).scam }
        println("UCI ham false alarms: ${fa.size}/${ham.size}; spam flagged: $sp/${spam.size}")
        fa.forEach { println("FA: $it  <- ${ScamSignals.assess("", it).reasons}") }
        if (System.getenv("ULTRA_SMS_DEBUG") != null) spam.filter { !ScamSignals.assess("", it).scam }.take(40)
            .forEach { println("MISS: $it  <- ${ScamSignals.assess("", it).reasons}") }
    }
}
