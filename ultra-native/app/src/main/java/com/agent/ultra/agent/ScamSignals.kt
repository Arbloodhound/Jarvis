package com.agent.ultra.agent

/**
 * Does this message look like a scam, and why — in words a person can act on.
 *
 * Deterministic, on-device, no model: the same message always gets the same
 * answer, and the answer is a list of reasons, not a score to trust. It runs on
 * incoming notifications (see [EventTrigger.ScamTrigger]) and feeds
 * [com.agent.ultra.gate.ScamWatch], which is what lets the gate refuse to send
 * money, a code or a message to a number that came out of a flagged text.
 *
 * ## What a scam text is made of
 *
 * Nearly every one pairs an **ask** (buy gift cards, share a code, pay by wire or
 * crypto, open a link, confirm your details, call this number) with **pressure**
 * (a threat to your account or from the law, a bank or government name, "Mum,
 * it's me on a new number", a prize, "don't tell anyone"). Either half alone is
 * ordinary: a bank really does text "suspicious activity on your card", a shop
 * really does send links. So this flags only:
 *
 * - an ask no legitimate sender makes — gift cards as payment, or sharing a code
 *   you were sent;
 * - any ask together with heavy pressure;
 * - payment by wire / crypto / payment app together with urgency.
 *
 * "Urgent" on its own is not heavy pressure: marketing says "today only" all day.
 *
 * ## Why so strict
 *
 * The same reason [Disagreement] is: a warning that fires on every delivery
 * notice gets ignored, and then it protects nobody. A missed scam leaves the
 * person exactly where they were before this file existed; a false alarm teaches
 * them to dismiss the next, real one.
 */
object ScamSignals {

    enum class Sign(val words: String, val ask: Boolean = false, val heavy: Boolean = false) {
        GIFT_CARDS("asks for gift cards", ask = true),
        CODE_REQUEST("asks you to share a code", ask = true),
        ODD_PAYMENT("asks for payment by wire, crypto or a payment app", ask = true),
        PERSONAL_INFO("asks you to confirm your account or card details", ask = true),
        LINK("has a link to open", ask = true),
        LOOKALIKE("has a link pretending to be a company it isn't", ask = true, heavy = true),
        MONEY_REQUEST("asks you to send or pay money", ask = true),
        CALL_NUMBER("asks you to call a number", ask = true),
        URGENCY("says it's urgent"),
        THREAT("threatens your account, or legal action", heavy = true),
        AUTHORITY("claims to be a bank, tax office, delivery firm or government", heavy = true),
        FAMILY("says it's family in trouble or on a new number", heavy = true),
        PRIZE("says you've won or are owed money", heavy = true),
        SECRECY("asks you to keep it secret", heavy = true),
    }

    data class Assessment(
        val scam: Boolean,
        val signs: List<Sign>,
        /** Phone numbers, hosts, emails and cashtags the message points at. */
        val targets: Set<String>,
    ) {
        /** "asks for gift cards, says it's urgent" — for a toast, the gate, or speech. */
        val reasons: String get() = signs.joinToString(", ") { it.words }
    }

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val GIFT_CARD = rx("""\b(gift ?cards?|google ?play (cards?|codes?)|itunes (cards?|codes?)|steam (cards?|codes?)|apple (gift )?cards?|amazon (gift )?cards?|ebay (gift )?cards?|vanilla (visa|cards?)|razer gold)\b""")
    // Buying one is ordinary (a shop sells them); SENDING what's on it is the scam. So "send the
    // codes / a photo of the back / scratch it" is decisive, while "buy" needs pressure too.
    private val GC_SEND = rx("""\b(send|text|read|give|email|share)\b(\s+\w+){0,3}?\s+(codes?|numbers|pictures?|photos?|pics?)\b|\bscratch\b|numbers on the back""")
    private val GC_BUY = rx("""\b(buy|purchase|pick up|get me)\b|\bpay\b(\s+\w+){0,3}?\s+(with|in|using)\b""")

    // "send me the code" asks; "do not share this code" warns. The negation check looks back
    // a few words so the warning every real OTP text carries never counts as the ask.
    // Only a code the SERVICE sent — "what's the code for the wifi" from family is not this.
    private val CODE_ASK = rx("""\b(send|give|tell|share|forward|text|read|reply with|what'?s|what is)\b(\s+\w+){0,3}?\s+(verification code|security code|confirmation code|login code|sign-?in code|otp|one-time (code|password|passcode|pin)|\d-digit code|code (we|i|they) (just )?(sent|texted)|code you (just )?(got|received))\b""")
    private val NEGATION = rx("""\b(not|never|don'?t|do not|no one|nobody|won'?t)\b""")

    private val ODD_PAY = rx("""\b(wire (transfer|the money|money|funds)|western union|moneygram|bitcoin|btc|crypto(currency)?|usdt|tether|bitcoin atm|zelle|cash ?app|venmo|paypal friends|(safe|secure|protected) (account|wallet))\b""")
    private val MONEY_ASK = rx("""\b(pay|transfer|send|wire|lend|move)\b(\s+\w+){0,3}?\s+(money|cash|funds|bill|rent|fee|fine|something|it|[£$€]\s?\d[\d,.]*)""")
    private val PERSONAL = rx("""\b(confirm(ing)?|verify(ing)?|updat(e|ing)|validat(e|ing)|re-?enter(ing)?|unlock(ing)?|restor(e|ing))\b(\s+\w+){0,3}?\s+(account|card|bank|banking|password|login|log-in|details|information|identity|ssn|social security|medicare number)\b""")
    private val CALL = rx("""\bcall\b(\s+\w+){0,3}?\s+(\+?\d[\d\s().-]{6,}\d)""")

    private val URGENT = rx("""\b(urgent(ly)?|immediately|right away|right now|asap|as soon as possible|within \d+ ?(hours?|hrs?|minutes?|mins?)|final (notice|warning|reminder)|act now|last chance|expires? today)\b""")
    private val THREAT = rx("""\b((account|card|profile|number|service)\b(\s+\w+){0,3}?\s+(locked|suspended|closed|frozen|restricted|limited|disabled|deactivated|terminated|disconnected|cut off|shut off)|arrest(ed)?|warrant|legal action|lawsuit|court (date|order|summons|case)|summons|penalt(y|ies)|deport(ed|ation)?|police|sheriff)\b""")
    private val AUTHORITY = rx("""\b(social security|medicare|tax (refund|office|rebate)|customs (fee|charge|duty)|(fraud|security) (department|dept|team)|royal mail|fedex|evri|toll (service|road|charges?)|ez-?pass)\b""")
    /** Acronyms only in capitals: "ups" is also the end of "check-ups". */
    private val AUTHORITY_CAPS = Regex("""\b(IRS|HMRC|SSA|USPS|UPS|DHL|DVLA|HMRC)\b""")
    // Not "hi mum" alone — real children text that every day. The new-number story is the tell.
    private val FAMILY = rx("""(\bnew number\b|\b(old |my )?phone (broke|is broken|died|got stolen)\b|\b(lost|broke|dropped|cracked) my phone\b|\bin jail\b|\bbail (money|out)\b|\bpost(ing)? bail\b)""")
    private val PRIZE = rx("""\b(you('ve| have)? won|you are a winner|winner|(been |are )?awarded (a|with|£|\$)|selected (2|to) receive|(cash|bonus|caller) prize|prize (reward|jackpot|draw)|guaranteed (a )?(£|\$|cash|prize)|claim (your )?(prize|reward|refund|gift|payment|money|funds|cash)|lottery|sweepstakes?|unclaimed|you('re| are) owed|owed a refund)\b""")
    private val SECRET = rx("""\b((don'?t|do not) (tell|say anything to|mention (this|it) to) (anyone|anybody|mum|mom|dad|your family|the bank)|keep (this|it) (between us|secret|quiet|to yourself)|(don'?t|do not) hang up)\b""")

    private val URL = rx("""\b((https?://)?((\d{1,3}\.){3}\d{1,3}|[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,24})(/\S*)?)""")
    private val PHONE = Regex("""(\+?\d[\d\s().-]{5,}\d)""")
    private val EMAIL = rx("""\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,24}\b""")
    private val CASHTAG = Regex("""(?<![\w$])\$[A-Za-z][A-Za-z0-9_-]{1,19}\b""")

    /** Words that end in a dot-something but are not hosts ("e.g", "a.m"). */
    private val NOT_HOSTS = setOf("e.g", "i.e", "a.m", "p.m", "u.s", "u.k", "etc")

    fun assess(title: String, text: String): Assessment {
        val body = "$title\n$text"
        val signs = linkedSetOf<Sign>()
        val giftSend = GIFT_CARD.containsMatchIn(body) && GC_SEND.containsMatchIn(body)
        if (giftSend || (GIFT_CARD.containsMatchIn(body) && GC_BUY.containsMatchIn(body))) signs += Sign.GIFT_CARDS
        if (CODE_ASK.findAll(body).any { m -> !NEGATION.containsMatchIn(body.substring(maxOf(0, m.range.first - 24), m.range.first + m.value.length)) }) {
            signs += Sign.CODE_REQUEST
        }
        if (ODD_PAY.containsMatchIn(body)) signs += Sign.ODD_PAYMENT
        if (PERSONAL.containsMatchIn(body)) signs += Sign.PERSONAL_INFO
        val hosts = hosts(body)
        // A company's own site is not a sign; its name on someone else's site is the strongest one.
        val foreign = hosts.filterNot { official(it) }
        if (foreign.isNotEmpty()) signs += Sign.LINK
        if (foreign.any { lookalike(it) }) signs += Sign.LOOKALIKE
        if (MONEY_ASK.containsMatchIn(body)) signs += Sign.MONEY_REQUEST
        if (CALL.containsMatchIn(body)) signs += Sign.CALL_NUMBER
        if (URGENT.containsMatchIn(body)) signs += Sign.URGENCY
        if (THREAT.containsMatchIn(body)) signs += Sign.THREAT
        if (AUTHORITY.containsMatchIn(body) || AUTHORITY_CAPS.containsMatchIn(body)) signs += Sign.AUTHORITY
        if (FAMILY.containsMatchIn(text) || FAMILY.containsMatchIn(title)) signs += Sign.FAMILY
        if (PRIZE.containsMatchIn(body)) signs += Sign.PRIZE
        if (SECRET.containsMatchIn(body)) signs += Sign.SECRECY

        val asks = signs.filter { it.ask }
        val heavy = signs.filter { it.heavy }
        val scam = giftSend || Sign.CODE_REQUEST in signs ||
            (Sign.GIFT_CARDS in signs && (Sign.URGENCY in signs || heavy.isNotEmpty())) ||
            (asks.isNotEmpty() && heavy.isNotEmpty()) ||
            (Sign.ODD_PAYMENT in signs && Sign.URGENCY in signs)

        // Order the reasons: what they want first, then how they push.
        if (Sign.LOOKALIKE in signs) signs -= Sign.LINK        // one reason, not two, for one link
        val ordered = signs.sortedBy { if (it.ask) 0 else 1 }
        return Assessment(scam, ordered, if (scam) targets(body, hosts) else emptySet())
    }

    private fun hosts(body: String): Set<String> {
        val out = mutableSetOf<String>()
        for (m in URL.findAll(body)) {
            val raw = m.value
            // an email's domain is not a link; the email is its own target
            if (m.range.first > 0 && body[m.range.first - 1] == '@') continue
            val host = normHost(raw) ?: continue
            if (host in NOT_HOSTS) continue
            // a bare word.word needs a plausible TLD to count; "3.50" and "file.txt" are not links
            val tld = host.substringAfterLast('.')
            if (tld.all { it.isDigit() } && !host.matches(Regex("""(\d{1,3}\.){3}\d{1,3}"""))) continue
            // no scheme, no www, no path: only a real, common TLD makes "word.word" a link
            // ("Mr.Smith", "file.txt" and "3.50" are not)
            val bare = !raw.contains("://") && !raw.lowercase().startsWith("www.") && !raw.contains('/')
            if (bare && tld !in COMMON_TLDS) continue
            out += host
        }
        return out
    }

    /** Brands scams imitate, as a host label (split on dots and dashes). */
    private val BRANDS = setOf(
        "usps", "ups", "fedex", "dhl", "royalmail", "evri", "hermes", "amazon", "apple", "appleid", "icloud",
        "paypal", "venmo", "zelle", "cashapp", "chase", "wellsfargo", "wf", "bofa", "bankofamerica", "citi",
        "netflix", "microsoft", "google", "coinbase", "irs", "hmrc", "ssa", "medicare", "ezpass", "walmart",
    )
    private val OFFICIAL = setOf(
        "usps.com", "ups.com", "fedex.com", "dhl.com", "royalmail.com", "evri.com", "amazon.com", "amazon.co.uk",
        "amzn.to", "apple.com", "icloud.com", "paypal.com", "venmo.com", "zellepay.com", "cash.app", "chase.com",
        "wellsfargo.com", "bankofamerica.com", "citi.com", "netflix.com", "microsoft.com", "google.com",
        "coinbase.com", "irs.gov", "ssa.gov", "medicare.gov", "gov.uk", "e-zpassny.com", "walmart.com",
    )

    fun official(host: String) = OFFICIAL.any { host == it || host.endsWith(".$it") }

    fun lookalike(host: String) = !official(host) && host.split('.', '-').any { it in BRANDS }

    private val DATE = Regex("""\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}""")

    private val COMMON_TLDS = setOf(
        "com", "net", "org", "info", "biz", "io", "co", "uk", "us", "ca", "au", "de", "fr", "nl", "in",
        "gov", "edu", "top", "xyz", "site", "online", "club", "shop", "app", "link", "live", "me", "ly",
        "cc", "icu", "vip", "buzz", "cfd", "sbs", "cyou", "support", "help", "services", "delivery",
    )

    private fun targets(body: String, hosts: Set<String>): Set<String> {
        val out = mutableSetOf<String>()
        out += hosts.map { "host:$it" }
        for (m in EMAIL.findAll(body)) out += "email:" + m.value.lowercase()
        for (m in PHONE.findAll(body)) {
            if (DATE.matches(m.value.trim())) continue          // 2026-09-19 is not a phone number
            normPhone(m.value)?.let { out += "phone:$it" }
        }
        for (m in CASHTAG.findAll(body)) out += "cashtag:" + m.value.lowercase()
        return out
    }

    /** Targets inside any text (a tool argument, a request) in the same normal form. */
    fun targetsIn(text: String): Set<String> = targets(text, hosts(text))

    fun normHost(raw: String): String? {
        var s = raw.lowercase().trim().trimEnd('.', ',', ')', '!', '?', ';', ':')
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        s = s.removePrefix("www.")
        return s.takeIf { it.contains('.') && it.length >= 4 }
    }

    /**
     * Digits only, at least 7 (shorter is a code, a price or a date). A North American
     * number is compared on its last 10 digits so +1 555 010 9999 and (555) 010-9999
     * are the same number; anything else must match in full.
     */
    fun normPhone(raw: String): String? {
        val d = raw.filter { it.isDigit() }
        if (d.length < 7 || d.length > 15) return null
        return if (d.length == 11 && d.startsWith("1")) d.substring(1) else d
    }
}
