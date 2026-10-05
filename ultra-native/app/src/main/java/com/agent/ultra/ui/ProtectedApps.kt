package com.agent.ultra.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.agent.ultra.AgentAccessibilityService

/**
 * Apps the agent must never read or touch.
 *
 * The accessibility service already had a blocklist; nothing ever put anything
 * in it. This is the list, and the reason it matters: an accessibility service
 * can read whatever is on screen, and whatever it reads is sent to the cloud
 * model as a tool result. "Don't act in my bank" is not enough — "don't look"
 * is the actual requirement.
 */
object ProtectedApps {

    /**
     * Package-name fragments that suggest an app holds money, identity, or
     * credentials. Used only to pre-tick the list on first run — a guess the
     * user can see and change, never a silent decision.
     */
    private val SENSITIVE_HINTS = listOf(
        // Learned by reading a real phone's 204 apps rather than guessing:
        // the first list caught 20 and missed at least 40 that mattered.
        "discover", "experian", "equifax", "transunion", "creditkarma",
        "affirm", "klarna", "afterpay", "lendmark", "sofi", "chime",
        "stripe", "square", "quickbooks", "paychex", "adp", "gusto",
        "fidelity", "schwab", "vanguard", "etrade", "robinhood", "tradingview",
        "uniswap", "opensea", "monero", "changenow", "changelly", "dexscreener",
        "coinmarketcap", "trustapp", "rabby", "coinomi", "ledger", "trezor",
        "lottery", "auction", "ezpass", "turnpike", "lifelock", "grants",
        "docusign", "adobe.reader", "keychain", "openkeychain", "ssh",
        "termius", "auditor", "termux", "vnc", "teamviewer", "anydesk",
        "torproject", "orbot", "vpn", "cyberghost", "tailscale", "protonmail", "proton",
        "mychart", "anthem", "epic", "cvs", "walgreens", "goodrx", "teladoc",
        "signal", "securesms", "telegram", "whatsapp", "orca", "messenger",
        "outlook", "gmail", "slack", "discord", "snapchat",
        "familylink", "classdojo", "remind101", "apptegy", "kidshome",
        "playconsole", "adsmanager", "pages.app", "shopify", "seller",
        "banking", "fiid", "creditunion", "fcu",
        "bank", "chase", "wellsfargo", "citi", "capitalone", "usaa", "hsbc",
        "barclays", "lloyds", "santander", "revolut", "monzo", "n26",
        "paypal", "venmo", "cashapp", "zelle", "wise",
        "wallet", "spay", "gpay", "googlepay", "applepay",
        "coinbase", "binance", "kraken", "crypto", "metamask",
        "authenticator", "lastpass", "1password", "bitwarden", "dashlane",
        "keepass", "authy", "duo",
        "健康", "health", "myfitnesspal",
        "turbotax", "hrblock", "taxact",
    )

    /** True when the agent may only enter apps the user has chosen. */
    fun allowlistMode(context: Context): Boolean =
        context.getSharedPreferences(AgentAccessibilityService.BLOCK_PREFS, Context.MODE_PRIVATE)
            .getBoolean(AgentAccessibilityService.MODE_KEY, false)

    fun setAllowlistMode(context: Context, on: Boolean) {
        context.getSharedPreferences(AgentAccessibilityService.BLOCK_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(AgentAccessibilityService.MODE_KEY, on).apply()
        AgentAccessibilityService.loadBlockedPackages(context)
    }

    fun allowed(context: Context): Set<String> =
        context.getSharedPreferences(AgentAccessibilityService.BLOCK_PREFS, Context.MODE_PRIVATE)
            .getStringSet(AgentAccessibilityService.ALLOW_KEY, emptySet()) ?: emptySet()

    fun setAllowed(context: Context, packages: Set<String>) {
        context.getSharedPreferences(AgentAccessibilityService.BLOCK_PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(AgentAccessibilityService.ALLOW_KEY, HashSet(packages)).apply()
        AgentAccessibilityService.loadBlockedPackages(context)
    }

    fun toggleAllowed(context: Context, pkg: String, on: Boolean) {
        val next = allowed(context).toMutableSet()
        if (on) next += pkg else next -= pkg
        setAllowed(context, next)
    }

    fun blocked(context: Context): Set<String> =
        context.getSharedPreferences(AgentAccessibilityService.BLOCK_PREFS, Context.MODE_PRIVATE)
            .getStringSet(AgentAccessibilityService.BLOCK_KEY, emptySet()) ?: emptySet()

    fun setBlocked(context: Context, packages: Set<String>) {
        context.getSharedPreferences(AgentAccessibilityService.BLOCK_PREFS, Context.MODE_PRIVATE)
            .edit()
            // A fresh set: SharedPreferences hands back the same mutable
            // instance it stored, and editing that in place does not persist.
            .putStringSet(AgentAccessibilityService.BLOCK_KEY, HashSet(packages))
            .apply()
        AgentAccessibilityService.loadBlockedPackages(context)
    }

    fun toggle(context: Context, pkg: String, on: Boolean) {
        val next = blocked(context).toMutableSet()
        if (on) next += pkg else next -= pkg
        setBlocked(context, next)
    }

    data class Entry(
        val pkg: String,
        val label: String,
        val protected: Boolean,
        val suggested: Boolean,
        val allowed: Boolean,
    )

    /** Launchable third-party apps, protected ones first. */
    fun installed(context: Context): List<Entry> {
        val pm = context.packageManager
        val blocked = blocked(context)
        val allowedSet = allowed(context)
        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != context.packageName }
            .map { info: ApplicationInfo ->
                Entry(
                    pkg = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    protected = info.packageName in blocked,
                    suggested = looksSensitive(info.packageName),
                    allowed = info.packageName in allowedSet,
                )
            }
            .sortedWith(compareByDescending<Entry> { it.allowed }
                .thenByDescending { it.protected }
                .thenByDescending { it.suggested }
                .thenBy { it.label.lowercase() })
            .toList()
    }

    /**
     * Guess whether a package holds money, identity or credentials.
     *
     * Substring matching alone is too blunt: "tor" flagged Calculator and
     * Avatar Editor, because calcula-tor and edi-tor contain it. Short hints
     * must sit on a token boundary — package names are dotted and
     * lowercase, so the separators are '.', '_' and '-'.
     */
    fun looksSensitive(pkg: String): Boolean {
        val p = pkg.lowercase()
        val tokens = p.split('.', '_', '-').filter { it.isNotBlank() }
        return SENSITIVE_HINTS.any { hint ->
            if (hint.length <= 4) tokens.any { it == hint || it.startsWith(hint) }
            else p.contains(hint)
        }
    }

    /**
     * On first run, pre-tick the apps that look sensitive. Returns true when
     * it wrote something, so the UI can say what it did rather than leaving
     * the user to discover it.
     */
    fun seedIfUnset(context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            AgentAccessibilityService.BLOCK_PREFS, Context.MODE_PRIVATE
        )
        if (prefs.contains("seeded")) return false
        val pm = context.packageManager
        val guesses = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { it.packageName }
            .filter { looksSensitive(it) }
            .toSet()
        // Safe by default on a fresh install: the agent can reach nothing
        // until the user picks. Seeding the protected list still runs, so the
        // guesses are there if they switch to a block list later.
        prefs.edit().putBoolean("seeded", true)
            .putBoolean(AgentAccessibilityService.MODE_KEY, true).apply()
        if (guesses.isNotEmpty()) setBlocked(context, blocked(context) + guesses)
        AgentAccessibilityService.loadBlockedPackages(context)
        return guesses.isNotEmpty()
    }
}
