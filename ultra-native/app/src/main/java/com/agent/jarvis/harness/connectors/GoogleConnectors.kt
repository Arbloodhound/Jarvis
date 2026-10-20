package com.agent.jarvis.harness.connectors

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Google connectors in the §1 priority order: gmail, google_calendar, google_drive, google_docs.
 * Each one is a thin wrapper over Google's REST API (no Google SDK, no Play services — the owner's
 * phone is de-Googled). Requests go through [AuthedTransport] with the connector's credential handle,
 * bound to that service's API hosts only.
 *
 * Connecting needs an OAuth client the owner registers in Google Cloud ([GoogleOAuth.clientId]);
 * until then [connect] says so plainly and every call returns NeedsConnect. The OAuth redirect
 * capture (an activity for the redirect URI) is not built yet — see the report.
 * SOURCE-ONLY: no call has been made against Google from the phone.
 */
abstract class GoogleConnector(
    protected val vault: CredentialVault,
    protected val transport: AuthedTransport,
    protected val oauth: GoogleOAuth,
) : Connector {

    /** The API hosts this connector's token may be sent to. */
    abstract val hosts: Set<String>
    abstract val scopes: List<String>
    /** A cheap authenticated GET used as the health check. */
    abstract val statusUrl: String

    protected fun handle(): String? = vault.handleFor(id())

    override fun isConnected(): Boolean = handle() != null

    override fun connect(): ConnectFlow = oauth.flowFor(id(), scopes)

    override suspend fun status(): ConnectorStatus {
        val h = handle() ?: return ConnectorStatus(ConnectorState.NOT_CONNECTED)
        val r = transport.send(HttpReq("GET", statusUrl), h)
        return when (r.code) {
            in 200..299 -> ConnectorStatus(ConnectorState.CONNECTED)
            401 -> ConnectorStatus(ConnectorState.EXPIRED, "sign-in expired")
            403 -> ConnectorStatus(ConnectorState.ERROR, "access not granted for this scope")
            else -> ConnectorStatus(ConnectorState.ERROR, "HTTP ${r.code}")
        }
    }

    protected suspend fun get(url: String): JSONObject = call(HttpReq("GET", url))
    protected suspend fun post(url: String, body: JSONObject): JSONObject = call(HttpReq("POST", url, body = body.toString()))

    private suspend fun call(req: HttpReq): JSONObject {
        val h = handle() ?: throw IllegalStateException("not connected")
        val r = transport.send(req, h)
        if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code}")
        return if (r.body.isBlank()) JSONObject() else JSONObject(r.body)
    }

    protected fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** OAuth settings for the Google connectors. The redirect and token exchange live in the app. */
class GoogleOAuth(val clientId: String?, val redirectUri: String = "com.agent.jarvis:/oauth2redirect") {
    fun flowFor(connectorId: String, scopes: List<String>): ConnectFlow {
        if (clientId.isNullOrBlank()) return ConnectFlow.NeedsSetup(
            "Connecting $connectorId needs a Google OAuth client ID (an Android/installed-app client registered in " +
                "Google Cloud by you). Nothing is connected until then.")
        val verifierNote = "The app generates a PKCE verifier for this request."
        val url = "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&access_type=offline&prompt=consent" +
            "&client_id=" + URLEncoder.encode(clientId, "UTF-8") +
            "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8") +
            "&scope=" + URLEncoder.encode(scopes.joinToString(" "), "UTF-8")
        return ConnectFlow.OpenUrl(url, "Sign in to Google in the browser and allow only what's listed. $verifierNote")
    }
}

class GmailConnector(vault: CredentialVault, transport: AuthedTransport, oauth: GoogleOAuth) :
    GoogleConnector(vault, transport, oauth) {
    override fun id() = "gmail"
    override val hosts = setOf("gmail.googleapis.com")
    override val scopes = listOf("https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.compose")
    override val statusUrl = "https://gmail.googleapis.com/gmail/v1/users/me/profile"
    private val base = "https://gmail.googleapis.com/gmail/v1/users/me"

    override fun operations() = listOf(
        ConnectorOp("search", OpEffect.READ, "search mail", listOf("query")),
        ConnectorOp("read", OpEffect.READ, "read one message", listOf("id")),
        ConnectorOp("draft", OpEffect.WRITE, "save a draft (not sent)", listOf("to", "subject", "body")),
        ConnectorOp("send", OpEffect.SEND, "send an email as you", listOf("to", "subject", "body")),
    )

    override suspend fun run(op: ConnectorOp, args: Map<String, String>): String = when (op.name) {
        "search" -> {
            val n = (args["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 25)
            val list = get("$base/messages?maxResults=$n&q=${enc(args.getValue("query"))}").optJSONArray("messages") ?: JSONArray()
            val lines = ArrayList<String>()
            for (i in 0 until list.length()) {
                val id = list.getJSONObject(i).getString("id")
                val m = get("$base/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date")
                lines += "[$id] ${header(m, "Date")} · ${header(m, "From")} · ${header(m, "Subject")} — ${m.optString("snippet").take(160)}"
            }
            if (lines.isEmpty()) "No messages match." else lines.joinToString("\n")
        }
        "read" -> {
            val m = get("$base/messages/${enc(args.getValue("id"))}?format=full")
            "From: ${header(m, "From")}\nDate: ${header(m, "Date")}\nSubject: ${header(m, "Subject")}\n\n" +
                GmailText.body(m.optJSONObject("payload")).take(8000)
        }
        "draft" -> {
            val d = post("$base/drafts", JSONObject().put("message", JSONObject().put("raw", GmailText.raw(args))))
            "Draft saved (id ${d.optString("id")}). Not sent."
        }
        "send" -> {
            val m = post("$base/messages/send", JSONObject().put("raw", GmailText.raw(args)))
            "Sent to ${args["to"]} (message id ${m.optString("id")})."
        }
        else -> throw IllegalArgumentException(op.name)
    }

    private fun header(m: JSONObject, name: String): String {
        val hs = m.optJSONObject("payload")?.optJSONArray("headers") ?: return ""
        for (i in 0 until hs.length()) { val h = hs.getJSONObject(i); if (h.optString("name").equals(name, true)) return h.optString("value") }
        return ""
    }
}

/** Gmail message helpers. Pure, JVM-tested. */
object GmailText {
    private fun b64urlDecode(s: String): String =
        String(java.util.Base64.getUrlDecoder().decode(s.replace("=", "")), Charsets.UTF_8)

    /** The text of a message payload: the first text/plain part, else text/html with tags removed. */
    fun body(payload: JSONObject?): String {
        if (payload == null) return ""
        fun find(p: JSONObject, mime: String): String? {
            if (p.optString("mimeType") == mime) p.optJSONObject("body")?.optString("data")?.takeIf { it.isNotEmpty() }?.let { return b64urlDecode(it) }
            val parts = p.optJSONArray("parts") ?: return null
            for (i in 0 until parts.length()) find(parts.getJSONObject(i), mime)?.let { return it }
            return null
        }
        find(payload, "text/plain")?.let { return it.trim() }
        return find(payload, "text/html")?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    }

    /** RFC 822 message, base64url, for drafts and sends. Header injection (CR/LF in a header) is refused. */
    fun raw(args: Map<String, String>): String {
        val to = args.getValue("to"); val subject = args.getValue("subject")
        require(listOf(to, subject).none { '\r' in it || '\n' in it }) { "line break in a header" }
        val mime = "To: $to\r\nSubject: $subject\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n${args.getValue("body")}"
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toByteArray(Charsets.UTF_8))
    }
}

class GoogleCalendarConnector(vault: CredentialVault, transport: AuthedTransport, oauth: GoogleOAuth) :
    GoogleConnector(vault, transport, oauth) {
    override fun id() = "google_calendar"
    override val hosts = setOf("www.googleapis.com")
    override val scopes = listOf("https://www.googleapis.com/auth/calendar.events")
    override val statusUrl = "https://www.googleapis.com/calendar/v3/users/me/calendarList?maxResults=1"
    private val base = "https://www.googleapis.com/calendar/v3/calendars/primary/events"

    override fun operations() = listOf(
        ConnectorOp("list", OpEffect.READ, "list upcoming events", listOf("from")),
        ConnectorOp("create", OpEffect.WRITE, "add an event to your calendar", listOf("title", "start", "end")),
        ConnectorOp("invite", OpEffect.SEND, "add an event and send an invitation", listOf("title", "start", "end", "to")),
    )

    override suspend fun run(op: ConnectorOp, args: Map<String, String>): String = when (op.name) {
        "list" -> {
            val n = (args["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 50)
            val items = get("$base?singleEvents=true&orderBy=startTime&maxResults=$n&timeMin=${enc(args.getValue("from"))}")
                .optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) "No events." else (0 until items.length()).joinToString("\n") { i ->
                val e = items.getJSONObject(i)
                val s = e.optJSONObject("start"); val start = s?.optString("dateTime")?.ifEmpty { null } ?: s?.optString("date").orEmpty()
                "$start · ${e.optString("summary")}" + (e.optString("location").takeIf { it.isNotBlank() }?.let { " @ $it" } ?: "")
            }
        }
        "create", "invite" -> {
            val ev = JSONObject().put("summary", args.getValue("title"))
                .put("start", JSONObject().put("dateTime", args.getValue("start")))
                .put("end", JSONObject().put("dateTime", args.getValue("end")))
            args["location"]?.let { ev.put("location", it) }
            if (op.name == "invite") ev.put("attendees", JSONArray().put(JSONObject().put("email", args.getValue("to"))))
            val r = post(base + if (op.name == "invite") "?sendUpdates=all" else "?sendUpdates=none", ev)
            "Event created: ${r.optString("summary")} (${r.optString("id")})."
        }
        else -> throw IllegalArgumentException(op.name)
    }
}

class GoogleDriveConnector(vault: CredentialVault, transport: AuthedTransport, oauth: GoogleOAuth) :
    GoogleConnector(vault, transport, oauth) {
    override fun id() = "google_drive"
    override val hosts = setOf("www.googleapis.com")
    override val scopes = listOf("https://www.googleapis.com/auth/drive.readonly")
    override val statusUrl = "https://www.googleapis.com/drive/v3/about?fields=user"

    override fun operations() = listOf(
        ConnectorOp("search", OpEffect.READ, "find files by name", listOf("query")),
        ConnectorOp("read", OpEffect.READ, "read a file's text", listOf("id")),
    )

    override suspend fun run(op: ConnectorOp, args: Map<String, String>): String = when (op.name) {
        "search" -> {
            val q = "name contains '${args.getValue("query").replace("\\", "\\\\").replace("'", "\\'")}' and trashed = false"
            val files = get("https://www.googleapis.com/drive/v3/files?pageSize=20&fields=files(id,name,mimeType,modifiedTime)&q=${enc(q)}")
                .optJSONArray("files") ?: JSONArray()
            if (files.length() == 0) "No files match." else (0 until files.length()).joinToString("\n") { i ->
                val f = files.getJSONObject(i)
                "[${f.optString("id")}] ${f.optString("name")} (${f.optString("mimeType").substringAfterLast('.')}, ${f.optString("modifiedTime").take(10)})"
            }
        }
        "read" -> {
            val id = enc(args.getValue("id"))
            val meta = get("https://www.googleapis.com/drive/v3/files/$id?fields=name,mimeType")
            val google = meta.optString("mimeType").startsWith("application/vnd.google-apps")
            val url = if (google) "https://www.googleapis.com/drive/v3/files/$id/export?mimeType=text/plain"
                else "https://www.googleapis.com/drive/v3/files/$id?alt=media"
            val r = transport.send(HttpReq("GET", url), handle() ?: throw IllegalStateException("not connected"))
            if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code}")
            "${meta.optString("name")}\n\n${r.body.take(8000)}"
        }
        else -> throw IllegalArgumentException(op.name)
    }
}

class GoogleDocsConnector(vault: CredentialVault, transport: AuthedTransport, oauth: GoogleOAuth) :
    GoogleConnector(vault, transport, oauth) {
    override fun id() = "google_docs"
    override val hosts = setOf("docs.googleapis.com")
    override val scopes = listOf("https://www.googleapis.com/auth/documents")
    override val statusUrl = "https://docs.googleapis.com/v1/documents/__status_probe__"

    /** The probe document doesn't exist: 404 means the token works, 401 means it doesn't. */
    override suspend fun status(): ConnectorStatus {
        val h = handle() ?: return ConnectorStatus(ConnectorState.NOT_CONNECTED)
        val r = transport.send(HttpReq("GET", statusUrl), h)
        return when (r.code) {
            401 -> ConnectorStatus(ConnectorState.EXPIRED, "sign-in expired")
            403 -> ConnectorStatus(ConnectorState.ERROR, "access not granted for this scope")
            in 200..299, 404 -> ConnectorStatus(ConnectorState.CONNECTED)
            else -> ConnectorStatus(ConnectorState.ERROR, "HTTP ${r.code}")
        }
    }

    override fun operations() = listOf(
        ConnectorOp("read", OpEffect.READ, "read a document", listOf("id")),
        ConnectorOp("create", OpEffect.WRITE, "create a new document", listOf("title", "text")),
    )

    override suspend fun run(op: ConnectorOp, args: Map<String, String>): String = when (op.name) {
        "read" -> {
            val d = get("https://docs.googleapis.com/v1/documents/${enc(args.getValue("id"))}")
            d.optString("title") + "\n\n" + DocsText.plain(d).take(8000)
        }
        "create" -> {
            val d = post("https://docs.googleapis.com/v1/documents", JSONObject().put("title", args.getValue("title")))
            val id = d.getString("documentId")
            post("https://docs.googleapis.com/v1/documents/$id:batchUpdate", JSONObject().put("requests", JSONArray().put(
                JSONObject().put("insertText", JSONObject().put("location", JSONObject().put("index", 1)).put("text", args.getValue("text"))))))
            "Document created: ${args["title"]} (id $id)."
        }
        else -> throw IllegalArgumentException(op.name)
    }
}

/** Google Docs JSON → plain text. Pure, JVM-tested. */
object DocsText {
    fun plain(doc: JSONObject): String {
        val sb = StringBuilder()
        val content = doc.optJSONObject("body")?.optJSONArray("content") ?: return ""
        for (i in 0 until content.length()) {
            val els = content.getJSONObject(i).optJSONObject("paragraph")?.optJSONArray("elements") ?: continue
            for (j in 0 until els.length()) sb.append(els.getJSONObject(j).optJSONObject("textRun")?.optString("content").orEmpty())
        }
        return sb.toString().trim()
    }
}
