package com.agent.ultra.agent

import com.agent.ultra.harness.PromptFragment
import com.agent.ultra.harness.SessionEnvelope
import com.agent.ultra.harness.ToolNamespaceStore
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
        // Calls and texts (owner, 2026-10-19: the local model needs the cloud model's tools). "call mom"
        // gives a name: Brain resolves it from the contacts and the gate still asks before it dials.
        "call_phone", "call_answer", "call_end", "call_speaker", "sms_send",
        // Reading the screen as a picture, and the rest of the phone's details.
        "screen_ocr", "look_at_screen", "describe_screen", "read_screen_deep", "device_info",
        // The web, memory, saved routines and learn-by-watching (owner, 2026-10-19). Not here on purpose:
        // shop_*, tor_connect (shopping and onion reach), ask_claude / delegate / connector / artifact (cloud by nature).
        "open_url", "web_search", "web_browse", "ambient_recall", "trash", "forget",
        "recipe_run", "recipe_save", "recipe_list", "recipe_delete", "watch_me", "stop_watching", "cancel_watching",
    )

    /** A request with more than one job ("call mom and then text Sam"): the on-device loop goes on to a
     *  next tool after the first. Simple requests stop after one call (no extra model run). */
    fun compound(request: String): Boolean =
        Regex("\\b(and then|then|after that|afterwards|and also|, and| and )\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(IntentRouter.requestPart(request))

    /** At most this many tool calls for one request. */
    const val MAX_CHAIN = 4

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
    fun offlineRoute(input: String, canDecline: Boolean, chains: Boolean = false): Boolean {
        val request = IntentRouter.requestPart(input)
        if (!Brain.isSimpleLocalIntent(request, chains)) return false
        val tier = IntentRouter.route(request).tier
        return if (canDecline) tier != IntentRouter.Tier.DEEP else tier == IntentRouter.Tier.DEVICE
    }

    /** Think budget for the tool call: the DEVICE tier's — a tool call needs barely any thought. */
    val thinkBudget: Int get() = IntentRouter.Tier.DEVICE.thinkBudget

    /** Room after the trace: one JSON call is a few dozen tokens; this leaves slack for a
     * model that adds a sentence. Not [LocalPrompt.answerReserve] — that is for prose answers. */
    const val ANSWER_RESERVE = 160

    /** Core persona fragment of the tool prompt. */
    private val PERSONA = """
You are Jarvis, an Android agent running entirely on this phone, offline. Decide whether the user's message is a command one of these tools carries out, and reply with EXACTLY one JSON object and nothing else.
""".trim()

    /** The always-full tool lines: every tool in [TOOLS], unchanged from the fixed catalog. */
    private val CORE_TOOLS_TEXT = """
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
call_phone {"to":"name or number"}   (place a call; a name is looked up in contacts)
call_answer {}   (pick up the ringing call)
call_end {}   (hang up, or decline the ringing call)
call_speaker {"on":true|false}   (speakerphone during a call)
sms_send {"to":"name or number","message":"..."}   (send a text)
screen_ocr {}   (read the words in a picture of the screen: games, maps, images)
look_at_screen {"question":"..."}   (the vision model describes the screen or answers a question about it; slow)
describe_screen {}   (a description of what is on the screen)
read_screen_deep {}   (scroll the page and read all of it)
device_info {}   (model, Android version, hardware)
open_url {"url":"https://..."}   (open a link in the browser)
web_search {"query":"..."}   (search the internet; results come back as text)
web_browse {"task":"..."}   (open-ended browsing for a task)
ambient_recall {"query":"...","limit":5}   (search what the phone overheard earlier)
trash {"action":"list|restore","id":"..."}   (the trash: list or restore)
forget {"subject":"..."}   (forget something from memory; asks first)
recipe_run {"name":"..."}   (run a saved routine)
recipe_save {"name":"..."}   (save the previous successful run as a routine)
recipe_list {}   (list saved routines)
recipe_delete {"name":"..."}   (delete a saved routine)
watch_me {}   (start watching me do something to learn it)
stop_watching {"name":"..."}   (stop watching and save it under a name)
cancel_watching {}   (stop watching and throw it away)
""".trim()

    /** After the tool list: the way out and the worked examples. */
    private val TRAILER = """
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
call mom -> {"tool":"call_phone","params":{"to":"mom"}}
answer the call -> {"tool":"call_answer","params":{}}
hang up -> {"tool":"call_end","params":{}}
put it on speaker -> {"tool":"call_speaker","params":{"on":true}}
text Sam I am running late -> {"tool":"sms_send","params":{"to":"Sam","message":"I am running late"}}
read the words in the picture of my screen -> {"tool":"screen_ocr","params":{}}
what does my screen look like -> {"tool":"look_at_screen","params":{"question":""}}
read this whole page -> {"tool":"read_screen_deep","params":{}}
what phone is this -> {"tool":"device_info","params":{}}
search the web for ferry times -> {"tool":"web_search","params":{"query":"ferry times"}}
open example.com -> {"tool":"open_url","params":{"url":"https://example.com"}}
what did we say about the trip -> {"tool":"ambient_recall","params":{"query":"trip","limit":5}}
run my morning routine -> {"tool":"recipe_run","params":{"name":"morning"}}
save that as morning -> {"tool":"recipe_save","params":{"name":"morning"}}
what routines do I have -> {"tool":"recipe_list","params":{}}
show my trash -> {"tool":"trash","params":{"action":"list"}}
forget my old address -> {"tool":"forget","params":{"subject":"my old address"}}
watch me do this -> {"tool":"watch_me","params":{}}
set a timer for 5 minutes -> {"tool":"none","params":{}}
""".trim()

    /** The model asks for a deferred tool namespace's schemas with this (handled in Brain, not a Tools tool). */
    const val LOAD_NAMESPACE = "load_tool_namespace"

    /**
     * Deferred namespaces (namespace → full schema block). EMPTY on purpose: every tool in
     * [TOOLS] stays fully described, exactly as before, so no existing command needs an extra
     * load turn. Put a block here to make that group a one-line stub until the model loads it.
     */
    val DEFERRED_SCHEMAS: Map<String, String> = emptyMap()

    /** A fresh per-session store: [TOOLS] are core (always full); [deferred] start as stubs. */
    fun newNamespaceStore(deferred: Map<String, String> = DEFERRED_SCHEMAS): ToolNamespaceStore =
        ToolNamespaceStore(
            deferred,
            // Only mention load_tool_namespace when there is something to load.
            if (deferred.isEmpty()) CORE_TOOLS_TEXT
            else CORE_TOOLS_TEXT + "\n$LOAD_NAMESPACE {\"namespace\":\"name\"}   (load a deferred tool group listed below)",
            TOOLS,
        )

    /** The four-fragment envelope of the tool prompt. Tool schemas (core + loaded + stubs, then
     * the examples) are one fragment, so a namespace load changes only that one. No profile or
     * history: a device command stands alone. */
    fun envelope(store: ToolNamespaceStore): SessionEnvelope = SessionEnvelope(
        LocalEnvelope.fragments(PERSONA, "", "TOOLS:\n" + store.promptBlock() + "\n\n" + TRAILER, ""),
        store.activeNamespaces().map { PromptFragment.sha256Hex(it) },
    )

    /** The system text for [store]'s current state. With no deferred namespaces this equals [SYSTEM]. */
    fun systemFor(store: ToolNamespaceStore): String = LocalEnvelope.systemText(envelope(store).fragments, "\n\n")

    val SYSTEM: String = systemFor(newNamespaceStore())

    /** ChatML prompt in the model's own template, ending in the opened <think> block
     * (pass startsInThink = true). No history: a device command stands on its own, and
     * every prompt token is prefill time on the phone. [store] carries the namespaces
     * loaded so far this session; null means the plain [SYSTEM]. */
    fun prompt(request: String, recalled: String? = null, store: ToolNamespaceStore? = null): String {
        val base = if (store == null) SYSTEM else systemFor(store)
        val system = if (recalled.isNullOrBlank()) base
            else "$base\n\nA request like this previously worked with: $recalled"
        return LocalPrompt.build(system, emptyList(), IntentRouter.requestPart(request))
    }

    sealed class Parsed {
        data class Call(val tool: String, val params: JSONObject) : Parsed()
        /** The whole reply is one offered tool's name and nothing else; params come from the
         * request. A name mentioned inside prose or JSON is not this. */
        data class BareName(val tool: String) : Parsed()
        /** The model asked for a deferred tool namespace's schemas; not a device action. */
        data class LoadNamespace(val namespace: String) : Parsed()
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
            tool == LOAD_NAMESPACE -> call.second.let { p ->
                Parsed.LoadNamespace(p.optString("namespace", p.optString("name", "")).trim())
            }
            tool in TOOLS -> Parsed.Call(tool, call.second)
            else -> Parsed.Unparsed
        }
    }
}
