package com.agent.jarvis.agent

/**
 * "If I already asked Jarvis to do something, it shouldn't ask me to confirm" — the owner's
 * decision of 2026-10-08 (option B): a commitment tap the person asked for in their own words goes
 * ahead without the confirmation card. Everything else still asks:
 *  - anything that can cost money (and any screen showing a price),
 *  - deleting, wiping, resetting, uninstalling, signing out,
 *  - agreeing to terms, and labels Jarvis can't read,
 *  - sending a message to anyone who is not already a contact named in the request and shown on
 *    the screen ("messaging someone new"),
 *  - anything Jarvis decided on its own (the request doesn't contain a matching verb).
 * The Disagreement check (request vs. screen numbers) still runs first and still stops.
 * Settings → "Don't ask when I asked for it" turns this off. Pure, JVM-tested.
 */
object DirectRequest {

    /** Commitments that always ask, even when the person asked for them. */
    private val ALWAYS_ASK = setOf(
        // money
        "pay", "purchase", "buy", "checkout", "order", "reorder", "transfer", "withdraw", "deposit",
        "subscribe", "renew", "donate", "tip", "bid", "authorize", "authorise", "book", "reserve",
        "bezahlen", "zahlen", "kaufen", "bestellen", "payer", "acheter", "commander", "pagar", "comprar",
        // deletion and the irreversible
        "delete", "remove", "cancel subscription", "unsubscribe", "uninstall", "restart", "reboot",
        "power off", "shut down", "reset", "factory reset", "erase", "sign out", "log out",
        "move to trash", "move to bin", "empty trash", "empty bin", "discard", "clear data",
        "clear storage", "clear all data", "wipe", "format storage", "format sd card", "format card",
        "format phone", "format device", "format drive", "loschen", "supprimer", "eliminar", "borrar",
        // terms, and what can't be read
        "agree", "accept", ActionGate.UNREADABLE,
    )

    /** Message-sending commitments: also need a known contact (see [skipConfirmation]). */
    private val MESSAGE_WORDS = setOf("send", "senden", "envoyer", "enviar")

    /** Commitment word → request verbs that mean "the person asked for exactly this". */
    private val ASKED_BY = mapOf(
        "send" to listOf("send", "text", "message", "msg", "reply", "tell", "email", "sms", "whatsapp", "signal"),
        "senden" to listOf("send", "senden", "text", "message"),
        "envoyer" to listOf("send", "envoyer", "text", "message"),
        "enviar" to listOf("send", "enviar", "text", "message"),
        "post" to listOf("post", "publish", "share", "tweet", "toot"),
        "publish" to listOf("publish", "post"),
        "submit" to listOf("submit"),
        "confirm" to listOf("confirm"),
        "install" to listOf("install", "download"),
    )

    /** "€12", "$5.99", "12,50 €", "USD 20": a price on screen means money may be involved. */
    private val PRICE = Regex("""[€$£¥₹]\s?\d|\d\s?[€$£¥₹]|\b(usd|eur|gbp|cad|aud)\s?\d""", RegexOption.IGNORE_CASE)

    fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun hasWord(text: String, word: String): Boolean = " $text ".contains(" $word ")

    /**
     * True = press without the confirmation card. [request]: the person's own words for this
     * run. [commitWord]: what ActionGate.commitmentIn matched. [screen]: the current screen text.
     * [contacts]: display names of the phone's contacts (only consulted for messages).
     */
    fun skipConfirmation(request: String, commitWord: String, screen: String, contacts: Collection<String>): Boolean {
        if (commitWord in ALWAYS_ASK) return false
        val verbs = ASKED_BY[commitWord] ?: return false
        val req = fold(request)
        if (req.isEmpty() || verbs.none { hasWord(req, it) }) return false
        if (PRICE.containsMatchIn(screen)) return false
        if (commitWord in MESSAGE_WORDS) {
            val scr = fold(screen)
            // A recipient counts as known when one of a contact's name words (3+ letters) is in
            // the request AND on the screen: the person named them, and it's them on screen.
            return contacts.any { name ->
                fold(name).split(" ").any { it.length >= 3 && hasWord(req, it) && hasWord(scr, it) }
            }
        }
        return true
    }
}
