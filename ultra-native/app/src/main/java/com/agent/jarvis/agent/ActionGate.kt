package com.agent.jarvis.agent

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The per-action gate for UI navigation.
 *
 * The policy gate checks that react_navigate's *goal* traces to what the user
 * asked for. It has nothing to say about the taps that follow: approving
 * "book me a table" approved every tap inside the app, including one labelled
 * "Confirm and pay". This closes that.
 *
 * The rule is deliberately narrow. Reading, scrolling, going back and tapping
 * ordinary controls carry on untouched — a gate that interrupts constantly
 * gets approved reflexively, which is worse than no gate. Only a tap whose
 * target reads like a commitment stops and asks.
 */
object ActionGate {

    data class Pending(
        val action: String,
        val label: String,
        val app: String,
        val reason: String,
    )

    var pending by mutableStateOf<Pending?>(null)
        private set

    private var waiter: CompletableDeferred<Boolean>? = null
    private val ui = CoroutineScope(Dispatchers.Main.immediate)

    private fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else ui.launch { block() }
    }

    /**
     * Ask the operator. Returns false if they decline or never answer —
     * an unanswered prompt must never become an approval.
     */
    suspend fun approve(action: String, label: String, app: String, reason: String,
                        /** The card's own wording, when it isn't a button press (e.g. "forget"). */
                        title: String? = null, body: String? = null): Boolean {
        val d = CompletableDeferred<Boolean>()
        waiter = d
        onUi { pending = Pending(action, label, app, reason) }
        android.util.Log.i(TAG, "PAUSED: $action on \"$label\" in $app (matched \"$reason\")")
        // The navigator drives another app, so Ultra is in the background when this fires. A
        // card nobody can see is a gate that silently denies everything after two minutes.
        //
        // The question goes ON TOP of the app it is about, as an accessibility overlay. Bringing
        // Ultra forward instead made the app underneath redraw when it came back: Markor dropped
        // its selection, "Delete" was gone, and an approved delete could never happen
        // (2026-09-20). Only when the overlay cannot be drawn is Ultra brought forward, the old way.
        val svc = com.agent.jarvis.AgentAccessibilityService.getInstance()
        val drawn = try {
            svc?.showGateOverlay(
                title ?: "Jarvis is about to press \u201C$label\u201D",
                body ?: "in $app. This commits something (matched \u201C$reason\u201D). Nothing happens unless you say so.",
                { resolve(true) }, { resolve(false) },
            ) ?: false
        } catch (e: Exception) { false }
        if (!drawn) bringUltraForward()
        var ok = false
        try {
            ok = withTimeoutOrNull(TIMEOUT_MS) { d.await() } ?: false
        } finally {
            // Exactly once per question, whatever the answer (approved, denied, timed out, or the
            // run cancelled — which commits nothing, so BLOCKED). The matched word only, never the
            // on-screen label.
            com.agent.jarvis.gate.GateAuditLog.recordAction(auditWord(reason), ok)
        }
        try { svc?.hideGateOverlay() } catch (_: Exception) {}
        onUi { pending = null }
        waiter = null
        android.util.Log.i(TAG, "RESOLVED: ${if (ok) "approved" else "denied"} — \"$label\"")
        return ok
    }

    /** The audit log's word for a match: [UNREADABLE] is a sentence, so it is logged as "unreadable". */
    internal fun auditWord(reason: String): String = if (reason == UNREADABLE) "unreadable" else reason

    fun resolve(approved: Boolean) {
        waiter?.complete(approved)
    }

    /** True while a question is waiting for the person. */
    val asking: Boolean get() = waiter?.isCompleted == false

    /** Deny anything outstanding — used when a run is abandoned. */
    fun cancelOutstanding() {
        try { com.agent.jarvis.AgentAccessibilityService.getInstance()?.hideGateOverlay() } catch (_: Exception) {}
        waiter?.complete(false)
        onUi { pending = null }
    }

    /**
     * Does this control commit something? Returns the matched word, or null.
     *
     * Matching is positional, not just "contains": a button is imperative
     * ("Place your order", "Buy now", "Send"), whereas a navigation item that
     * happens to share the word is not ("Your Orders", "Order history"). A
     * false positive costs one tap of yours; a false negative costs money.
     */
    fun commitmentIn(rawLabel: String): String? {
        // Accents are folded first so "Löschen" reads as "loschen", not "l schen".
        val folded = java.text.Normalizer.normalize(rawLabel.trim().lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace("ß", "ss")
        val label = folded.replace(Regex("[^a-z0-9 &]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        // A label in a script this list cannot read ("Оплатить", "支付") is not evidence that
        // the button is harmless. Asking costs one tap; guessing could cost money. Icon glyphs
        // and arrows ("⋮", "→") carry no letters and still pass.
        val unreadable = folded.any { it.isLetter() && it !in 'a'..'z' }
        if (label.isBlank()) return if (unreadable) UNREADABLE else null
        if (NAVIGATION.any { label == it }) return null
        for (word in COMMIT_WORDS) {
            if (label == word) return word
            if (label.startsWith("$word ")) return word
            if (label.contains(" $word ") && label.split(" ").size <= 5) return word
            if (label.endsWith(" $word") && label.split(" ").size <= 4) return word
        }
        // "Оплатить 500 ₽" keeps its digits through the filter; the words are still unread.
        return if (unreadable) UNREADABLE else null
    }

    private fun bringUltraForward() {
        try {
            val ctx = com.agent.jarvis.UltraApplication.instance
            ctx.startActivity(
                android.content.Intent(ctx, com.agent.jarvis.MainActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "could not surface the confirmation", e)
        }
    }

    private const val TAG = "UltraActionGate"
    /** The reason given when the label is in a script the word list cannot read. */
    const val UNREADABLE = "a label in a language I can't read"
    private const val TIMEOUT_MS = 120_000L

    /** Words that mean something happens that is hard to take back. */
    private val COMMIT_WORDS = listOf(
        "pay", "purchase", "buy", "checkout", "order", "reorder",
        "confirm", "submit", "send", "post", "publish",
        "transfer", "withdraw", "deposit", "subscribe", "renew",
        "delete", "remove", "cancel subscription", "unsubscribe",
        "authorize", "authorise", "agree", "accept",
        "book", "reserve", "donate", "tip", "bid",
        "install", "uninstall",
        // Irreversible for the phone itself. The navigator's planner proposed "Restart the
        // phone" for "open the weather app" and "open samsung notes" (learnrun, 2026-09-19).
        "restart", "reboot", "power off", "shut down", "reset", "factory reset",
        "erase", "sign out", "log out",
        // Confirm-dialog wording for deletion and wiping. "Trash"/"Bin" alone stay navigation
        // (see NAVIGATION); moving something INTO them is the commitment.
        "move to trash", "move to bin", "empty trash", "empty bin", "discard",
        "clear data", "clear storage", "clear all data", "wipe",
        // Formatting storage only: a bare "format" is every text editor's formatting menu.
        "format storage", "format sd card", "format card", "format phone", "format device", "format drive",
        // A few of the same words in the languages most often met on a phone. Not exhaustive.
        "bezahlen", "zahlen", "kaufen", "senden", "loschen", "bestellen",
        "payer", "acheter", "envoyer", "supprimer", "commander",
        "pagar", "comprar", "enviar", "eliminar", "borrar",
        // Deliberately NOT here: "allow". Permission dialogs say Allow and Don't allow on
        // ordinary screens; gating both stopped a run dead on a contacts permission prompt
        // (AndroidWorld, 2026-09-19) and would train tap-through on a real phone.
        // Deliberately absent: "sign" and "apply". "Sign in" and "Apply
        // filters" appear constantly, and a gate that fires on ordinary
        // navigation gets approved reflexively, which is worse than no gate.
        // Signing a document and applying for credit are real commitments;
        // they are rare enough on a phone to accept the gap knowingly.
    )

    /** Labels that share a word with the list above but only navigate. */
    private val NAVIGATION = listOf(
        "your orders", "my orders", "orders", "order history", "order status",
        "track order", "returns & orders", "returns and orders",
        "sent", "sent messages", "subscriptions", "my subscriptions",
        "deleted", "deleted items", "trash", "bin", "purchases", "my purchases",
        "buy again", "payments", "payment methods", "confirmations",
        // Cookie banners are on every site. Consent to tracking is a real
        // decision, but not the class this gate protects — money, messages
        // and deletion — and its frequency would train the operator to tap
        // through without reading.
        "accept all", "accept cookies", "accept all cookies",
        "accept necessary", "accept and continue",
    )
}
