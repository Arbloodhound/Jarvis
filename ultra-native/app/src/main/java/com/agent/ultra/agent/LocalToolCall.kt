package com.agent.ultra.agent

import org.json.JSONObject

/**
 * Offline device commands on a thinking model (Qwen3 Thinking, ChatML, template-opened
 * <think>). Pure, JVM-tested: the routing decision, the prompt, and reading the tool call
 * out of what is left after the trace.
 *
 * Why it exists: with no cloud provider configured, every request used to go to the
 * text-only offline answer, so "turn on the flashlight" got a chat reply and nothing
 * happened. The 1B fast-path prompt cannot be used as is — it has no think budget, and a
 * <think> trace in front of the JSON breaks the parse. Here the prompt is the model's own
 * template, the trace is capped and hidden by [ThinkBudget], and only the post-think text
 * reaches [extract].
 */
object LocalToolCall {

    /** The tools the on-device loop offers — the same subset as the 1B fast path. */
    val TOOLS = setOf(
        "flashlight_toggle", "wifi_toggle", "bluetooth_toggle", "do_not_disturb",
        "volume_set", "alarm_set", "note_create", "app_launch",
        "battery_status", "clipboard_read", "clipboard_write",
        // Full device control through the accessibility service (owner, 2026-10-07: "take a
        // screenshot" got a chat reply saying it couldn't, because none of these were offered).
        "screenshot", "global_action", "read_text_on_screen", "notification_read",
        "media_play", "media_next", "settings_open", "device_location",
        // Everything on the phone (owner, 2026-10-07: "unrestricted access to the phone and everything on it").
        "sms_read", "contacts_read", "system_info",
    )

    /** What the model says when the message is not a command for one of [TOOLS]. */
    const val NONE = "none"

    /**
     * Should an offline request try the on-device tool loop before the text answer?
     *
     * [Brain.isSimpleLocalIntent] is the catalog match (and already refuses compounds and
     * URLs). The router vetoes the questions it shares words with — "how do I open a jar",
     * "explain how bluetooth works" — which are DEEP and want an answer, not a tool.
     *
     * [canDecline]: the thinking-model prompt has a {"tool":"none"} way out, so keyword-shaped
     * questions the router calls NORMAL ("what is the volume of a sphere") can be offered to it.
     * The 1B prompt has no way out — it always names a tool — so it only gets what the router
     * positively calls a DEVICE command. Routing is on the command alone when ambient context
     * wraps it.
     */
    fun offlineRoute(input: String, canDecline: Boolean): Boolean {
        val request = IntentRouter.requestPart(input)
        if (!Brain.isSimpleLocalIntent(request)) return false
        val tier = IntentRouter.route(request).tier
        return if (canDecline) tier != IntentRouter.Tier.DEEP else tier == IntentRouter.Tier.DEVICE
    }

    /** Think budget for the tool call: the DEVICE tier's — a tool call needs barely any thought. */
    val thinkBudget: Int get() = IntentRouter.Tier.DEVICE.thinkBudget

    /** Room after the trace: one JSON call is a few dozen tokens; this leaves slack for a
     * model that adds a sentence. Not [LocalPrompt.answerReserve] — that is for prose answers. */
    const val ANSWER_RESERVE = 160

    val SYSTEM = """
You are Jarvis, an Android agent running entirely on this phone, offline. Decide whether the user's message is a command one of these tools carries out, and reply with EXACTLY one JSON object and nothing else.

TOOLS:
flashlight_toggle {"on":true|false}
wifi_toggle {"on":true|false}
bluetooth_toggle {"on":true|false}
do_not_disturb {"on":true|false}
volume_set {"percent":0-100}
alarm_set {"hour":0-23,"minute":0-59,"label":"..."}
note_create {"text":"..."}
app_launch {"target":"app name"}
battery_status {}
clipboard_read {}
clipboard_write {"text":"..."}
screenshot {}
global_action {"action":"home|back|recents|notifications|quick_settings|lock_screen"}
read_text_on_screen {}
notification_read {"limit":10}
media_play {}   (play or pause music)
media_next {}   (next track)
settings_open {"page":"wifi|bluetooth|display|sound|battery|apps|location|..."}
device_location {}
sms_read {"limit":5}   (read my texts / messages)
contacts_read {"name":"..."}   (a contact's number or details)
system_info {}   (storage, Wi-Fi, Bluetooth, battery, phone details)

If no tool above does what the message asks (a question, chat, a timer, anything else), reply {"tool":"none","params":{}}.

EXAMPLES:
turn on the flashlight -> {"tool":"flashlight_toggle","params":{"on":true}}
open chrome -> {"tool":"app_launch","params":{"target":"chrome"}}
set an alarm for 7 30 am -> {"tool":"alarm_set","params":{"hour":7,"minute":30,"label":"Jarvis alarm"}}
what's my battery level -> {"tool":"battery_status","params":{}}
turn off wifi -> {"tool":"wifi_toggle","params":{"on":false}}
take a screenshot -> {"tool":"screenshot","params":{}}
go to the home screen -> {"tool":"global_action","params":{"action":"home"}}
pull down my notifications -> {"tool":"global_action","params":{"action":"notifications"}}
read my notifications -> {"tool":"notification_read","params":{"limit":10}}
what's on my screen -> {"tool":"read_text_on_screen","params":{}}
pause the music -> {"tool":"media_play","params":{}}
skip this song -> {"tool":"media_next","params":{}}
lock the phone -> {"tool":"global_action","params":{"action":"lock_screen"}}
read my last texts -> {"tool":"sms_read","params":{"limit":5}}
what's Sam's number -> {"tool":"contacts_read","params":{"name":"Sam"}}
how much storage do I have -> {"tool":"system_info","params":{}}
set a timer for 5 minutes -> {"tool":"none","params":{}}
""".trim()

    /** ChatML prompt in the model's own template, ending in the opened <think> block
     * (pass startsInThink = true). No history: a device command stands on its own, and
     * every prompt token is prefill time on the phone. */
    fun prompt(request: String, recalled: String? = null): String {
        val system = if (recalled.isNullOrBlank()) SYSTEM
            else "$SYSTEM\n\nA request like this previously worked with: $recalled"
        return LocalPrompt.build(system, emptyList(), IntentRouter.requestPart(request))
    }

    sealed class Parsed {
        data class Call(val tool: String, val params: JSONObject) : Parsed()
        /** The whole reply is one offered tool's name and nothing else; params come from the
         * request. A name mentioned inside prose or JSON is not this. */
        data class BareName(val tool: String) : Parsed()
        /** The model said this is not a device command. */
        object Declined : Parsed()
        /** No usable call: prose, broken JSON, or a tool outside [TOOLS]. */
        object Unparsed : Parsed()
    }

    /**
     * The visible text after the trace, cleaned: anything up to a leftover `</think>`
     * (a trace the stream filter did not see open), any complete `<think>…</think>`, an
     * unterminated `<think>` tail, end-of-turn markers, whitespace.
     */
    fun visibleText(raw: String): String {
        var s = raw
        val close = s.lastIndexOf(ThinkBudget.CLOSE)
        if (close >= 0) s = s.substring(close + ThinkBudget.CLOSE.length)
        val open = s.indexOf(ThinkBudget.OPEN)
        if (open >= 0) s = s.substring(0, open)
        s = s.replace("<|im_end|>", "").replace("<|endoftext|>", "")
        return s.trim()
    }

    /** The one call in the post-think text. Tools outside [TOOLS] are not run: the loop
     * offered only these, and the screen/navigation tools need the cloud navigator. */
    fun extract(raw: String): Parsed {
        val text = visibleText(raw)
        if (text.isEmpty()) return Parsed.Unparsed
        text.trim('`', ' ', '\n', '.').let { if (it in TOOLS) return Parsed.BareName(it) }
        val call = ModelOutput.toolCall(text) ?: return Parsed.Unparsed
        val tool = call.first.trim()
        return when {
            tool.equals(NONE, ignoreCase = true) -> Parsed.Declined
            tool in TOOLS -> Parsed.Call(tool, call.second)
            else -> Parsed.Unparsed
        }
    }
}
