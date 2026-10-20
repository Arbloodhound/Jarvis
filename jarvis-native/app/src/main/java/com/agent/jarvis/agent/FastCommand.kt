package com.agent.jarvis.agent

import org.json.JSONObject

/**
 * Obvious one-shot voice/text commands turned into a tool call WITHOUT a model run: "flashlight on",
 * "volume 50", "hang up", "call mom". The whole request must be the command (anchored), not a
 * question and not a chain. The call still goes through the gate and confirm cards like any other
 * ([Brain.runLocalCall]); anything that does not match falls through to the model. Pure, JVM-tested.
 * (Owner, 2026-10-19: fast voice. The model run, 5-50 s on the phone, was the delay for commands.)
 */
object FastCommand {
    private val LEAD = Regex("^(?:hey jarvis[, ]*|jarvis[, ]*|please[, ]*|can you[, ]*|could you[, ]*)+", RegexOption.IGNORE_CASE)
    private val TAIL = Regex("[\\s.!?]*(?:please)?[\\s.!?]*$", RegexOption.IGNORE_CASE)
    private val CHAIN = Regex("\\b(and then|then|after that|and|also)\\b", RegexOption.IGNORE_CASE)

    private fun onOff(s: String) = s.equals("on", true)
    private fun call(tool: String, vararg kv: Pair<String, Any>) =
        tool to JSONObject().also { j -> kv.forEach { (k, v) -> j.put(k, v) } }

    private class Rule(val re: Regex, val build: (MatchResult) -> Pair<String, JSONObject>)

    private fun r(p: String, b: (MatchResult) -> Pair<String, JSONObject>) = Rule(Regex(p, RegexOption.IGNORE_CASE), b)

    private val RULES = listOf(
        r("^(?:turn|switch) (on|off) (?:the |my )?(?:flashlight|torch)$") { call("flashlight_toggle", "on" to onOff(it.groupValues[1])) },
        r("^(?:flashlight|torch) (on|off)$") { call("flashlight_toggle", "on" to onOff(it.groupValues[1])) },
        r("^(?:turn|switch) (on|off) (?:the |my )?wi-?fi$") { call("wifi_toggle", "on" to onOff(it.groupValues[1])) },
        r("^wi-?fi (on|off)$") { call("wifi_toggle", "on" to onOff(it.groupValues[1])) },
        r("^(?:turn|switch) (on|off) (?:the |my )?bluetooth$") { call("bluetooth_toggle", "on" to onOff(it.groupValues[1])) },
        r("^bluetooth (on|off)$") { call("bluetooth_toggle", "on" to onOff(it.groupValues[1])) },
        r("^(?:turn|switch) (on|off) (?:do not disturb|dnd)$") { call("do_not_disturb", "on" to onOff(it.groupValues[1])) },
        r("^(?:do not disturb|dnd) (on|off)$") { call("do_not_disturb", "on" to onOff(it.groupValues[1])) },
        r("^(?:turn|switch) (on|off) (?:the )?(?:dark (?:mode|theme)|night mode)$") { call("dark_mode", "on" to onOff(it.groupValues[1])) },
        r("^(?:dark (?:mode|theme)|night mode) (on|off)$") { call("dark_mode", "on" to onOff(it.groupValues[1])) },
        r("^(?:set )?(?:the )?(?:media )?volume (?:to )?(\\d{1,3}) ?(?:percent|%)?$") {
            call("volume_set", "percent" to it.groupValues[1].toInt().coerceIn(0, 100))
        },
        r("^(?:what'?s|what is) (?:my )?battery(?: level| status| percentage)?$|^battery(?: level| status)?$") { call("battery_status") },
        r("^(?:take a )?screen ?shot$") { call("screenshot") },
        r("^(?:go (?:to the )?home(?: screen)?|go home)$") { call("global_action", "action" to "home") },
        r("^go back$") { call("global_action", "action" to "back") },
        r("^(?:show |open )?(?:the )?recent apps$") { call("global_action", "action" to "recents") },
        r("^(?:pull down|open|show) (?:the |my )?notifications?(?: shade)?$") { call("global_action", "action" to "notifications") },
        r("^lock (?:the |my )?(?:phone|screen)$") { call("global_action", "action" to "lock_screen") },
        r("^(?:pause|play|resume)(?: the)?(?: music| song)?$") { call("media_play") },
        r("^(?:next|skip)(?: the| this)?(?: song| track)?$") { call("media_next") },
        r("^(?:hang up|end (?:the |this )?call)$") { call("call_end") },
        r("^(?:answer|pick up)(?: the| this| that)?(?: call| phone)?$") { call("call_answer") },
        r("^(?:put (?:it|the call) on speaker(?:phone)?|speaker(?:phone)? on|turn on (?:the )?speaker(?:phone)?)$") { call("call_speaker", "on" to true) },
        r("^(?:speaker(?:phone)? off|turn off (?:the )?speaker(?:phone)?|take (?:it|the call) off speaker(?:phone)?)$") { call("call_speaker", "on" to false) },
        r("^(?:where am i|what'?s my location|what is my location)$") { call("device_location") },
        // "call mom" / "call 604 555 0100": a name is looked up in the contacts, then the gate asks before dialing.
        r("^call (\\+?[a-z0-9][a-z0-9 .'()-]{1,38})$") { call("call_phone", "to" to it.groupValues[1].trim()) },
    )

    /** The tool call for [request] when the whole of it is one of the known commands, else null. */
    fun match(request: String): Pair<String, JSONObject>? {
        var s = IntentRouter.requestPart(request).trim()
        if (s.isEmpty() || s.length > 60 || s.contains('?')) return null
        s = LEAD.replace(s, "").let { TAIL.replace(it, "") }.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty() || CHAIN.containsMatchIn(s)) return null
        for (rule in RULES) rule.re.find(s)?.let { return rule.build(it) }
        return null
    }
}
