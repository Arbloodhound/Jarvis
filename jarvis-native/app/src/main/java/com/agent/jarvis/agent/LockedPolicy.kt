package com.agent.jarvis.agent

/**
 * What Jarvis may do for a voice at a LOCKED phone (said to ambient listening). Nothing private:
 * no memory, notes, messages, contacts, screen, apps, location or clipboard. Harmless things are
 * fine: flashlight, volume, music, alarms, battery, the time, general questions, and adding a note.
 */
object LockedPolicy {

    private val PRIVATE = Regex(
        "\\b(notes?|notebook|memor(y|ies)|remember|recall|heard|hear|said|say about|told|message|messages|texts?|sms|" +
            "emails?|inbox|contacts?|call|calls|calendar|photos?|pictures?|gallery|screenshot|screen ?shot|screen|" +
            "notifications?|clipboard|copy|paste|location|where am i|open|launch|apps?|settings|wi-?fi|bluetooth|" +
            "unlock|password|passcode|pin|code|bank|pay|payment|money|buy|order|send|delete|whatsapp|instagram|" +
            "who (is|was)|my (wife|husband|partner|boss|mum|mom|dad|friend|kids?))\\b",
        RegexOption.IGNORE_CASE,
    )

    /** May [request] run while the phone is locked? Adding a note may; anything private may not. */
    fun allowed(request: String): Boolean {
        val r = IntentRouter.requestPart(request).trim()
        if (r.isEmpty()) return false
        if (com.agent.jarvis.listen.NameInference.told(r) != null) return false
        if (com.agent.jarvis.listen.UserNotes.command(r) != null) return true
        if (MemoryFocus.detect(r) != null || MemoryFocus.screen(r) != null) return false
        if (MemoryBrief.wants(r).search) return false
        return !PRIVATE.containsMatchIn(r)
    }

    const val REFUSAL = "Unlock your phone for that one."
}
