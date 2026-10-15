package com.agent.ultra.harness.connectors

import com.agent.ultra.harness.ApprovalLevel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorsTest {

    private class Fake(var connected: Boolean = true) : Connector {
        val ran = ArrayList<String>()
        override fun id() = "fake"
        override fun isConnected() = connected
        override fun connect() = ConnectFlow.NeedsSetup("set me up")
        override suspend fun status() = ConnectorStatus(if (connected) ConnectorState.CONNECTED else ConnectorState.NOT_CONNECTED)
        override fun operations() = listOf(
            ConnectorOp("read", OpEffect.READ, "read", listOf("id")),
            ConnectorOp("send", OpEffect.SEND, "send", listOf("to", "body")),
            ConnectorOp("buy", OpEffect.PURCHASE, "buy", listOf("item")),
            ConnectorOp("leak", OpEffect.READ, "returns a code"))
        override suspend fun run(op: ConnectorOp, args: Map<String, String>): String {
            ran += op.name
            return if (op.name == "leak") "Your verification code is 481920. Reset: https://acct.example/reset?token=abcdef1234567890xyz" else "done ${op.name}"
        }
    }

    private class Grants(val recipients: Set<String> = emptySet()) : GrantCheck {
        override suspend fun covered(level: String, tool: String, argsJson: String, channel: String?, account: String?, recipient: String?) =
            level == ApprovalLevel.RECIPIENT && recipient in recipients
    }

    @Test fun notConnectedSurfacesTheConnectFlowAndRunsNothing() = runBlocking {
        val f = Fake(connected = false)
        val r = ConnectorRegistry(Grants()).apply { register(f) }.call("fake", "read", mapOf("id" to "1"))
        assertTrue(r is CallResult.NeedsConnect)
        assertTrue((r as CallResult.NeedsConnect).why.contains("nothing was done"))
        assertTrue(f.ran.isEmpty())
    }

    @Test fun readsNeedNoApprovalSendsNeedARecipientGrantOrTheExactApproval() = runBlocking {
        val f = Fake()
        val reg = ConnectorRegistry(Grants(setOf("mum@example.com"))).apply { register(f) }
        assertEquals(CallResult.Ok("done read"), reg.call("fake", "read", mapOf("id" to "1")))
        val ask = reg.call("fake", "send", mapOf("to" to "boss@example.com", "body" to "hi"))
        assertTrue(ask is CallResult.NeedsApproval)
        assertTrue((ask as CallResult.NeedsApproval).action.contains("boss@example.com"))
        assertEquals(listOf("read"), f.ran)
        // A token for different args doesn't carry over.
        val other = reg.approve(reg.approvalKey("fake", "send", mapOf("to" to "boss@example.com", "body" to "other")))
        assertTrue(reg.call("fake", "send", mapOf("to" to "boss@example.com", "body" to "hi"), other) is CallResult.NeedsApproval)
        val tok = reg.approve(ask.key)
        assertEquals(CallResult.Ok("done send"), reg.call("fake", "send", mapOf("to" to "boss@example.com", "body" to "hi"), tok))
        assertEquals(CallResult.Ok("done send"), reg.call("fake", "send", mapOf("to" to "mum@example.com", "body" to "hi")))
    }

    @Test fun purchasesAlwaysAskWhateverIsGranted() = runBlocking {
        val all = object : GrantCheck {
            override suspend fun covered(level: String, tool: String, argsJson: String, channel: String?, account: String?, recipient: String?) = true
        }
        val reg = ConnectorRegistry(all).apply { register(Fake()) }
        assertTrue(reg.call("fake", "buy", mapOf("item" to "x")) is CallResult.NeedsApproval)
    }

    @Test fun missingArgsAndUnknownOpsFail() = runBlocking {
        val reg = ConnectorRegistry(Grants()).apply { register(Fake()) }
        assertEquals(CallResult.Failed("missing 'id'"), reg.call("fake", "read", emptyMap()))
        assertTrue(reg.call("fake", "nope", emptyMap()) is CallResult.Failed)
        assertTrue(reg.call("ghost", "read", emptyMap()) is CallResult.Failed)
    }

    @Test fun resultsAreScrubbedOfCodesAndResetLinks() = runBlocking {
        val r = ConnectorRegistry(Grants()).apply { register(Fake()) }.call("fake", "leak", emptyMap()) as CallResult.Ok
        assertFalse(r.text.contains("481920"))
        assertFalse(r.text.contains("token=abcdef"))
        assertEquals(2, Regex("\\[credential:[0-9a-f]{8}]").findAll(r.text).count())
    }

    // ── SecretScrubber ──
    @Test fun scrubberKeepsOrdinaryNumbersAndLinks() {
        val s = SecretScrubber()
        val plain = "Order 48192 ships Tuesday. Track at https://shop.example/orders/48192. Call 0207 946 0000."
        assertEquals(plain, s.scrub(plain).text)
        assertEquals(1, s.scrub("481920 is your code").removed)
        assertEquals(1, s.scrub("OTP: 123-456 for login").removed)
        assertEquals(1, s.scrub("Sign in: https://app.example/magic-link?t=Zx91kQ").removed)
        assertTrue(SecretScrubber.isSensitiveLink("https://accounts.example/recover/AbCdEfGhIjKlMnOpQr"))
        assertFalse(SecretScrubber.isSensitiveLink("https://example.com/blog/how-to-reset-your-router"))
    }

    @Test fun heldValuesExpireAfterTenMinutes() {
        val s = SecretScrubber()
        val out = s.scrub("your verification code is 555123", nowMs = 0).text
        val id = Regex("\\[credential:([0-9a-f]{8})]").find(out)!!.groupValues[1]
        assertEquals("555123", s.reveal(id, nowMs = 1_000))
        assertEquals(null, s.reveal(id, nowMs = SecretScrubber.HOLD_MS + 1))
    }

    // ── AuthedTransport: host-bound handles ──
    private class Rec : HttpTransport {
        val seen = ArrayList<HttpReq>()
        var reply = HttpResp(200, "{}")
        override suspend fun send(req: HttpReq): HttpResp { seen += req; return reply }
    }

    @Test fun theTokenOnlyGoesToItsBoundHostsOverHttps() = runBlocking {
        val vault = InMemoryVault(); val rec = Rec(); val t = AuthedTransport(vault, rec)
        val h = vault.store("gmail", "SECRET-TOKEN", setOf("gmail.googleapis.com"))
        assertFalse("the handle doesn't contain the secret", h.contains("SECRET"))
        t.send(HttpReq("GET", "https://gmail.googleapis.com/x"), h)
        assertEquals("Bearer SECRET-TOKEN", rec.seen.single().headers["Authorization"])
        assertTrue(runCatching { t.send(HttpReq("GET", "https://evil.example/x"), h) }.exceptionOrNull() is AuthedTransport.HostNotAllowed)
        assertTrue(runCatching { t.send(HttpReq("GET", "http://gmail.googleapis.com/x"), h) }.exceptionOrNull() is AuthedTransport.HostNotAllowed)
        assertTrue(runCatching { t.send(HttpReq("GET", "https://gmail.googleapis.com.evil.example/x"), h) }.exceptionOrNull() is AuthedTransport.HostNotAllowed)
        assertEquals(1, rec.seen.size)
    }

    // ── Google connectors over a scripted transport ──
    private fun gmail(rec: Rec, connected: Boolean = true): Pair<GmailConnector, ConnectorRegistry> {
        val vault = InMemoryVault()
        if (connected) vault.store("gmail", "tok", setOf("gmail.googleapis.com"))
        val g = GmailConnector(vault, AuthedTransport(vault, rec), GoogleOAuth(clientId = null))
        return g to ConnectorRegistry(Grants()).apply { register(g) }
    }

    @Test fun gmailWithoutAnOAuthClientNeedsSetupAndCallsNothing() = runBlocking {
        val rec = Rec()
        val (_, reg) = gmail(rec, connected = false)
        val r = reg.call("gmail", "search", mapOf("query" to "invoice"))
        assertTrue(r is CallResult.NeedsConnect)
        assertTrue((r as CallResult.NeedsConnect).flow is ConnectFlow.NeedsSetup)
        assertTrue(rec.seen.isEmpty())
    }

    @Test fun gmailExpiredTokenIsReportedNotRetried() = runBlocking {
        val rec = Rec().apply { reply = HttpResp(401, "") }
        val (_, reg) = gmail(rec)
        val r = reg.call("gmail", "search", mapOf("query" to "invoice")) as CallResult.NeedsConnect
        assertTrue(r.why.contains("expired"))
        assertEquals(1, rec.seen.size)
    }

    @Test fun gmailReadDecodesTheBodyAndScrubsCodes() = runBlocking {
        val body = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("Hi! Your login code is 902211.".toByteArray())
        val rec = object : HttpTransport {
            override suspend fun send(req: HttpReq) = if (req.url.endsWith("/profile")) HttpResp(200, "{}") else HttpResp(200,
                JSONObject().put("payload", JSONObject().put("mimeType", "multipart/alternative")
                    .put("headers", org.json.JSONArray().put(JSONObject().put("name", "Subject").put("value", "Code")))
                    .put("parts", org.json.JSONArray().put(JSONObject().put("mimeType", "text/plain")
                        .put("body", JSONObject().put("data", body))))).toString())
        }
        val vault = InMemoryVault().apply { store("gmail", "tok", setOf("gmail.googleapis.com")) }
        val reg = ConnectorRegistry(Grants()).apply { register(GmailConnector(vault, AuthedTransport(vault, rec), GoogleOAuth(null))) }
        val r = reg.call("gmail", "read", mapOf("id" to "m1")) as CallResult.Ok
        assertTrue(r.text.contains("Subject: Code"))
        assertTrue(r.text.contains("Hi!"))
        assertFalse(r.text.contains("902211"))
    }

    @Test fun gmailRawRefusesHeaderInjection() {
        assertTrue(runCatching { GmailText.raw(mapOf("to" to "a@b.c\r\nBcc: x@y.z", "subject" to "s", "body" to "b")) }.isFailure)
        val raw = GmailText.raw(mapOf("to" to "a@b.c", "subject" to "Hello", "body" to "Body"))
        val decoded = String(java.util.Base64.getUrlDecoder().decode(raw))
        assertTrue(decoded.startsWith("To: a@b.c\r\nSubject: Hello\r\n"))
    }

    @Test fun docsPlainTextAndOAuthUrl() {
        val doc = JSONObject("""{"body":{"content":[{"paragraph":{"elements":[{"textRun":{"content":"Hello "}},{"textRun":{"content":"world\n"}}]}},{"sectionBreak":{}}]}}""")
        assertEquals("Hello world", DocsText.plain(doc))
        val flow = GoogleOAuth("abc.apps.googleusercontent.com").flowFor("gmail", listOf("s1", "s2")) as ConnectFlow.OpenUrl
        assertTrue(flow.url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(flow.url.contains("scope=s1+s2") || flow.url.contains("scope=s1%20s2"))
    }
}
