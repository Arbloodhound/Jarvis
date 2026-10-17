package com.agent.ultra.bench

/**
 * The cloud brain check (Jarvis Tests, button 6): what the cloud model is shown, what it can
 * actually use right now, and what it never gets. Lives in main only so the JVM tests can reach
 * it (like [KitReport]); the debug-only TestKitActivity supplies the live facts. Pure, no Android.
 */
object CloudBrainReport {

    private fun word(name: String) = Regex("(?<![a-z_])${Regex.escape(name)}(?![a-z_])")

    /** The real tools (gate manifest names) that [text] names as a whole word. */
    fun named(text: String, manifest: Set<String>): Set<String> =
        manifest.filter { word(it).containsMatchIn(text) }.toSortedSet()

    /** snake_case words in [text] that look like tool names but are not real tools. */
    fun unknownNames(text: String, manifest: Set<String>): Set<String> =
        Regex("(?<![A-Za-z_])[a-z]+(?:_[a-z]+)+(?![A-Za-z_])").findAll(text)
            .map { it.value }.filter { it !in manifest }.toSortedSet()

    /**
     * The question the live check sends, with the real system prompt in front of it. It is
     * sent straight to the provider, not through the brain, so nothing it answers is run.
     */
    const val PROBE = """DIAGNOSTIC from the owner's test app. This is not a task: call no tool and write no JSON.
1) List every tool name you can call, one per line, exactly as your instructions name it.
2) Then a line "CANNOT:" and, briefly, what you have been told you cannot do, will not see, or must not do.
3) Then a line "UNCLEAR:" and anything in your instructions that is ambiguous or contradicts itself (or "none")."""

    data class SelfCheck(
        /** Real tools the model listed. */
        val listed: Set<String>,
        /** Tools its prompt offers that it did not list (it may not know it has them). */
        val missed: Set<String>,
        /** Names it listed that are not real tools (it would call them and fail). */
        val invented: Set<String>,
    )

    /** Compares the model's own tool list (the part before "CANNOT:") with what it was offered. */
    fun selfCheck(reply: String, offered: Set<String>, manifest: Set<String>): SelfCheck {
        val list = reply.substringBefore("CANNOT:")
        val listed = named(list, manifest)
        return SelfCheck(listed, offered - listed, unknownNames(list, manifest))
    }

    /** One thing the phone does or doesn't allow right now. */
    data class Access(val name: String, val ok: Boolean)

    /** Which offered tools can't work without each kind of access (only the clear-cut ones). */
    val NEEDS: Map<String, List<String>> = mapOf(
        "Accessibility service" to listOf("react_navigate", "read_text_on_screen", "read_screen_deep",
            "describe_screen", "screenshot", "watch_me"),
        "Notification access" to listOf("notification_read"),
        "SMS read" to listOf("sms_read"),
        "SMS send" to listOf("sms_send"),
        "Contacts" to listOf("contacts_read"),
        "Location" to listOf("device_location"),
    )

    /** "tool — offered to the model, but <access> is off": each offered tool that can't work now. */
    fun deadTools(access: List<Access>, offered: Set<String>): List<String> =
        access.filter { !it.ok }.flatMap { a ->
            NEEDS[a.name].orEmpty().filter { it in offered }.map { "$it — offered to the model, but ${a.name} is off" }
        }

    /** What the cloud model never gets, by construction (from the code, not measured). */
    fun neverSees(feedbackChars: Int): List<String> = listOf(
        "One-time codes, password-reset and magic sign-in links in tool results: replaced by [credential:<id>]",
        "Tool, screen and web text beyond $feedbackChars characters per result; what it gets is marked untrusted",
        "Images: screenshots and look_at_screen run on the phone; the cloud only gets text",
        "Password fields: typing into them is refused, so the model can't fill them",
        "Chat history beyond the last 8 messages / 4000 characters",
        "Your API key and the approval cards (the app shows those; the model can't approve)",
    )

    /** Rough size of [chars] of English prompt text in tokens (about 4 characters each). */
    fun approxTokens(chars: Int): Int = (chars + 3) / 4

    /** One line per message: role, size, start of the text (line breaks flattened). */
    fun requestSummary(messages: List<Pair<String, String>>, preview: Int = 160): List<String> =
        messages.mapIndexed { i, (role, text) ->
            "#${i + 1} $role · ${text.length} chars · " + text.replace(Regex("\\s+"), " ").take(preview)
        }

    /** Just the host of [baseUrl] (the path and any query are left out of the report). */
    fun host(baseUrl: String): String = baseUrl.substringAfter("://").substringBefore('/').substringBefore('?')
}
