package com.agent.jarvis.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.agent.jarvis.AgentAccessibilityService

/**
 * Apps the agent must never read or touch. The accessibility service reads
 * whatever is on screen, and whatever it reads is sent to the cloud model as a
 * tool result.
 */
object ProtectedApps {

    /**
     * Package-name fragments that suggest an app holds money, identity, or
     * credentials. Used only to pre-tick the list on first run.
     */
    private val SENSITIVE_HINTS = listOf(
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

    /** Every app open to the agent, no lists (default on). */
    fun unrestricted(context: Context): Boolean =
        context.getSharedPreferences(AgentAccessibilityService.BLOCK_PREFS, Context.MODE_PRIVATE)
            .getBoolean(AgentAccessibilityService.UNRESTRICTED_KEY, true)

    fun setUnrestricted(context: Context, on: Boolean) {
        context.getSharedPreferences(AgentAccessibilityService.BLOCK_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(AgentAccessibilityService.UNRESTRICTED_KEY, on).apply()
        AgentAccessibilityService.loadBlockedPackages(context)
    }

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
     * Short hints must sit on a token boundary — package names are dotted and
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
     * it wrote something.
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
        // Fresh install: allowlist mode on; protected list still seeded.
        prefs.edit().putBoolean("seeded", true)
            .putBoolean(AgentAccessibilityService.MODE_KEY, true).apply()
        if (guesses.isNotEmpty()) setBlocked(context, blocked(context) + guesses)
        AgentAccessibilityService.loadBlockedPackages(context)
        return guesses.isNotEmpty()
    }
}
