package com.agent.ultra.agent

import android.content.Context

/**
 * The offline-only switch (Settings → AI PROVIDER (CLOUD) → "Offline only"): whether the cloud
 * brain and web search may be used. Model downloads are not covered — they still work.
 */
object NetPolicy {

    /**
     * What a web search returns while offline-only is on. Starts with "OFFLINE" because Brain
     * drops web results with that prefix (see the web step of the offline answer path).
     */
    const val OFFLINE_RESULT =
        "OFFLINE: offline-only mode is on — web search is off. Answer from what you know and say so."

    /** Pure, JVM-tested. The cloud brain may run only with a client and offline-only off. */
    fun cloudAllowed(offlineOnly: Boolean, hasClient: Boolean): Boolean = !offlineOnly && hasClient

    /** Pure, JVM-tested. Web search may run only with offline-only off. */
    fun webAllowed(offlineOnly: Boolean): Boolean = !offlineOnly

    /** The owner's setting (UltraPrefs, on by default). */
    fun offlineOnly(ctx: Context): Boolean = com.agent.ultra.ui.UltraPrefs.offlineOnly(ctx)
}
