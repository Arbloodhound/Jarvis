package com.agent.ultra.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Before the agent drives Tor Browser on screen: Tor Browser carries its own Tor. With a VPN on —
 * Orbot's VPN mode above all — its traffic would go Tor over Tor (slower, and the Tor Project
 * advises against it). The agent informs and asks: the call returns [VPN_NOTE] instead of acting,
 * and goes ahead once the owner says so ("vpnOk": true). Jarvis's own Tor (EmbeddedTor) is a
 * local proxy for its research only and never touches Tor Browser.
 */
object TorBrowserGuard {
    private val NAMES = Regex("tor\\s*browser|torbrowser|org\\.torproject\\.torbrowser", RegexOption.IGNORE_CASE)

    /** Pure. Does [text] (an app target, hint or goal) name Tor Browser? */
    fun mentionsTorBrowser(text: String): Boolean = NAMES.containsMatchIn(text)

    fun vpnActive(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (_: Exception) { false }

    const val VPN_NOTE = "Not started: a VPN is on, and Tor Browser runs its own Tor. If the VPN is Orbot's VPN mode, " +
        "Tor Browser would run Tor over Tor (slower, and the Tor Project advises against it) — ask the owner to switch " +
        "Orbot's VPN off first. If it's another VPN, it's the owner's call; when they say go ahead, call again with \"vpnOk\": true."

    /** [VPN_NOTE] when the call names Tor Browser, a VPN is on and the owner hasn't said go ahead; else null. */
    fun check(ctx: Context, text: String, vpnOk: Boolean): String? =
        if (!vpnOk && mentionsTorBrowser(text) && vpnActive(ctx)) VPN_NOTE else null
}
