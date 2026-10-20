package com.agent.jarvis.harness.connectors

import com.agent.jarvis.harness.ApprovalLevel

/**
 * §1 — every external service is a connector: a small tool-shaped wrapper around the vendor's API.
 * The agent never touches a vendor SDK; it calls [ConnectorRegistry.call] with an operation name and
 * JSON-ish args, and gets text back.
 *
 * Order of checks for every call ([ConnectorRegistry.call]):
 *  1. the connector exists and the operation exists,
 *  2. [Connector.status] — not connected → [CallResult.NeedsConnect] with the connect flow, and stop.
 *     Never a promised outcome.
 *  3. approval — reads need none; sends need a recipient grant or a fresh approval; writes need a
 *     fresh approval; purchases always ask (no lasting grant exists, anywhere).
 *  4. run, then [SecretScrubber] over the result (codes / reset links never reach the model).
 *
 * The approval card itself is shown by the app (ActionGate), not by the agent: a call that needs one
 * returns [CallResult.NeedsApproval] with the exact action described, and only an [ApprovalToken] the
 * app mints after the user's tap lets the same call through.
 */
interface Connector {
    fun id(): String
    fun isConnected(): Boolean
    /** How to connect; the app shows it. Never connects silently. */
    fun connect(): ConnectFlow
    /** Health check against the service (token valid, API reachable). */
    suspend fun status(): ConnectorStatus
    fun operations(): List<ConnectorOp>
    /** Runs [op]; only called by the registry after the checks above. */
    suspend fun run(op: ConnectorOp, args: Map<String, String>): String
}

enum class OpEffect { READ, WRITE, SEND, PURCHASE, DELETE }

data class ConnectorOp(
    val name: String,
    val effect: OpEffect,
    val description: String,
    val requiredArgs: List<String> = emptyList(),
) {
    /** The approval level the gate applies (ApprovalGrants levels). */
    val approvalLevel: String get() = when (effect) {
        OpEffect.READ -> ApprovalLevel.NONE
        OpEffect.SEND -> ApprovalLevel.RECIPIENT
        OpEffect.WRITE, OpEffect.DELETE -> ApprovalLevel.ONCE
        OpEffect.PURCHASE -> ApprovalLevel.ALWAYS_ASK
    }
}

enum class ConnectorState { CONNECTED, NOT_CONNECTED, EXPIRED, UNAVAILABLE, ERROR }

data class ConnectorStatus(val state: ConnectorState, val detail: String = "")

sealed class ConnectFlow {
    /** Open [url] in the browser (OAuth); the app captures the redirect. */
    data class OpenUrl(val url: String, val explanation: String) : ConnectFlow()
    /** The owner must set something up first (e.g. register an OAuth client). */
    data class NeedsSetup(val explanation: String) : ConnectFlow()
}

sealed class CallResult {
    data class Ok(val text: String) : CallResult()
    data class NeedsConnect(val connectorId: String, val flow: ConnectFlow, val why: String) : CallResult()
    /** The app must show the approval card for [action]; the agent can't approve it. */
    data class NeedsApproval(val key: String, val action: String, val level: String) : CallResult()
    data class Failed(val reason: String) : CallResult()
}

/**
 * Proof that the user approved one exact call. Only the app mints these (internal constructor, from
 * the approval card's callback); a model can't produce one from text.
 */
class ApprovalToken internal constructor(val key: String)

/** Durable grants, as [com.agent.jarvis.harness.ApprovalGrants] answers them; an interface so tests needn't use Room. */
interface GrantCheck {
    suspend fun covered(level: String, tool: String, argsJson: String, channel: String?, account: String?, recipient: String?): Boolean
}

class ConnectorRegistry(
    private val grants: GrantCheck,
    private val scrubber: SecretScrubber = SecretScrubber(),
) {
    private val connectors = LinkedHashMap<String, Connector>()

    fun register(c: Connector) { connectors[c.id()] = c }
    fun get(id: String): Connector? = connectors[id]
    fun all(): List<Connector> = connectors.values.toList()

    /** The stable key an approval binds to: connector, op and the exact sorted args. */
    fun approvalKey(connectorId: String, op: String, args: Map<String, String>): String =
        "$connectorId.$op|" + args.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    /** Called by the app's approval-card callback after the user approves [key]. */
    fun approve(key: String): ApprovalToken = ApprovalToken(key)

    suspend fun call(connectorId: String, opName: String, args: Map<String, String>,
                     approval: ApprovalToken? = null, account: String? = null): CallResult {
        val c = connectors[connectorId] ?: return CallResult.Failed("no connector '$connectorId'")
        val op = c.operations().firstOrNull { it.name == opName }
            ?: return CallResult.Failed("'$connectorId' has no operation '$opName'")
        op.requiredArgs.firstOrNull { args[it].isNullOrBlank() }?.let { return CallResult.Failed("missing '$it'") }

        val st = try { c.status() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ConnectorStatus(ConnectorState.ERROR, e.javaClass.simpleName)
        }
        if (st.state != ConnectorState.CONNECTED) {
            return CallResult.NeedsConnect(connectorId, c.connect(),
                "$connectorId isn't connected (${st.state.name.lowercase()}${if (st.detail.isNotBlank()) ": ${st.detail}" else ""}) — nothing was done.")
        }

        val key = approvalKey(connectorId, opName, args)
        val approved = approval?.key == key
        val covered = approved || when (op.approvalLevel) {
            ApprovalLevel.NONE -> true
            ApprovalLevel.ALWAYS_ASK -> false
            else -> grants.covered(op.approvalLevel, "$connectorId.$opName", key, connectorId, account, args["to"])
        }
        if (!covered) return CallResult.NeedsApproval(key, describe(connectorId, op, args), op.approvalLevel)

        return try {
            CallResult.Ok(scrubber.scrub(c.run(op, args)).text)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            CallResult.Failed("$connectorId.$opName failed: ${e.message?.take(160) ?: e.javaClass.simpleName}")
        }
    }

    /** The exact action, for the approval card: connector, operation and every argument. */
    fun describe(connectorId: String, op: ConnectorOp, args: Map<String, String>): String =
        "$connectorId: ${op.description}" + args.toSortedMap().entries.joinToString("") { "\n  ${it.key}: ${it.value.take(400)}" }

    /** The connector docs list for the model: id, connected or not, operations. */
    fun directory(): String = connectors.values.joinToString("\n") { c ->
        "- ${c.id()} (${if (c.isConnected()) "connected" else "not connected"}): " +
            c.operations().joinToString(", ") { "${it.name}[${it.effect.name.lowercase()}]" }
    }
}

/** [GrantCheck] over the durable grants in UltraDatabase (harness/ApprovalGrants.kt). */
class ApprovalGrantsCheck(private val grants: com.agent.jarvis.harness.ApprovalGrants) : GrantCheck {
    override suspend fun covered(level: String, tool: String, argsJson: String, channel: String?, account: String?, recipient: String?) =
        grants.isCovered(level, tool = tool, argsJson = argsJson, channel = channel, account = account, recipient = recipient)
}
