package com.agent.ultra.gate

import com.agent.ultra.agent.ScamSignals
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * scam_followup: the user naming a number is not enough when that number came
 * out of a message the scam detector flagged — getting them to name it is the scam.
 */
class ScamGateTest {

    private val manifest: Manifest = Manifest.fromJson(JSONObject(
        javaClass.classLoader!!.getResource("ultra.manifest.json")?.readText()
            ?: java.io.File("src/main/assets/ultra.manifest.json").readText()))

    @Before fun flag() {
        ScamWatch.clear()
        ScamWatch.remember(ScamSignals.assess("+1 555 123 4546",
            "Grandpa, I'm in jail. Wire bail money to 555-201-7788 via Western Union or pay at bail-help.top. Don't tell mom."), "+1 555 123 4546")
    }

    @After fun clear() = ScamWatch.clear()

    private fun check(req: String, tool: String, args: String): Gate.Verdict =
        Gate(manifest).enforceCall(Gate.Episode(req), tool, JSONObject(args))

    @Test fun userNamedScamNumberIsHeld() {
        val v = check("text 555-201-7788 that the money is on its way", "sms_send",
            """{"to":"5552017788","message":"the money is on its way"}""")
        assertFalse(v.allowed)
        assertEquals("scam_followup", v.rule)
        assertTrue(v.confirmable)
        assertFalse(v.autoApproved)
        assertTrue(v.violations.first().hint.contains("looks like a scam"))
    }

    @Test fun scamLinkIsHeld() {
        val v = check("open bail-help.top", "open_url", """{"url":"https://bail-help.top/pay"}""")
        assertFalse(v.allowed)
        assertEquals("scam_followup", v.rule)
    }

    /** Device run 2026-09-19: llama-3.3-70b drove "send 20 to $mike_torres88" into Cash App. */
    @Test fun appDrivingToAFlaggedCashtagIsHeld() {
        ScamWatch.remember(ScamSignals.assess("Unknown",
            "Hi grandma its Jake, new number. Need 600 on cash app to \$mike_torres88 today, urgent."), "Unknown")
        val v = check("open cash app and send 20 to \$mike_torres88 for lunch", "react_navigate",
            """{"goal":"send ${'$'}20 to ${'$'}mike_torres88 for lunch","appHint":"Cash App"}""")
        assertEquals("scam_followup", v.rule)
        assertFalse(v.autoApproved)
    }

    @Test fun searchingAboutItStaysFree() {
        val v = check("search is bail-help.top a scam", "web_search", """{"query":"is bail-help.top a scam"}""")
        assertTrue(v.violations.none { it.rule == "scam_followup" })
    }

    @Test fun otherNumbersUnaffected() {
        val v = check("text 555-909-1212 I'm running late", "sms_send",
            """{"to":"5559091212","message":"I'm running late"}""")
        assertTrue(v.violations.none { it.rule == "scam_followup" })
    }

    @Test fun confirmedByTheUserPasses() {
        val ep = Gate.Episode("text 555-201-7788 hello")
        ep.confirm("5552017788")
        val v = Gate(manifest).enforceCall(ep, "sms_send", JSONObject("""{"to":"5552017788","message":"hello"}"""))
        assertTrue(v.violations.none { it.rule == "scam_followup" })
    }

    @Test fun modelFacingTextSaysWhy() {
        val v = check("open bail-help.top", "open_url", """{"url":"https://bail-help.top/pay"}""")
        val msg = Gate.renderBlock(v)
        assertTrue(msg, msg.contains("looks like a scam") && msg.contains("Tell the user"))
    }
}
