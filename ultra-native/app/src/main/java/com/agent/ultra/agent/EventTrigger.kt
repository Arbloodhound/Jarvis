package com.agent.ultra.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Deterministic event triggers — reactions to phone events that don't need
 * the LLM. Each trigger is a pure function: event in, action out (or null
 * to skip). No brain, no cloud, no latency.
 *
 * Two kinds:
 * - [Trigger]: fires on incoming notifications (the listener calls [evaluate]).
 * - [SystemTrigger]: fires on system broadcasts (registered via [startSystemTriggers]).
 */
object EventTrigger {

    private const val TAG = "UltraTrigger"

    data class Action(
        val type: Type,
        val label: String,
        val data: String = "",
    ) {
        enum class Type { CLIPBOARD_COPY, TOAST, WARN }
    }

    interface Trigger {
        val name: String
        fun evaluate(context: Context, pkg: String, title: String, text: String): Action?
    }

    interface SystemTrigger {
        val name: String
        val intentFilter: IntentFilter
        fun evaluate(context: Context, intent: Intent): Action?
    }

    private val triggers = mutableListOf<Trigger>()
    private val systemTriggers = mutableListOf<SystemTrigger>()
    private val receivers = mutableListOf<BroadcastReceiver>()
    private var systemStarted = false

    fun register(trigger: Trigger) {
        triggers += trigger
        Log.i(TAG, "registered: ${trigger.name}")
    }

    fun registerSystem(trigger: SystemTrigger) {
        systemTriggers += trigger
        Log.i(TAG, "registered system: ${trigger.name}")
    }

    fun evaluate(context: Context, pkg: String, title: String, text: String): List<Action> {
        val actions = mutableListOf<Action>()
        for (t in triggers) {
            try {
                val a = t.evaluate(context, pkg, title, text) ?: continue
                actions += a
                Log.i(TAG, "${t.name} fired: ${a.type} ${a.label}")
            } catch (e: Exception) {
                Log.w(TAG, "${t.name} failed: ${e.message}")
            }
        }
        return actions
    }

    fun interface ActionExecutor {
        fun execute(action: Action)
    }

    fun startSystemTriggers(context: Context, executor: ActionExecutor) {
        if (systemStarted) return
        systemStarted = true
        for (st in systemTriggers) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    try {
                        val a = st.evaluate(ctx, intent) ?: return
                        Log.i(TAG, "${st.name} fired: ${a.type} ${a.label}")
                        executor.execute(a)
                    } catch (e: Exception) {
                        Log.w(TAG, "${st.name} failed: ${e.message}")
                    }
                }
            }
            context.registerReceiver(receiver, st.intentFilter)
            receivers += receiver
            Log.i(TAG, "system receiver registered: ${st.name}")
        }
    }

    fun stopSystemTriggers(context: Context) {
        for (r in receivers) {
            try { context.unregisterReceiver(r) } catch (_: Exception) {}
        }
        receivers.clear()
        systemStarted = false
    }

    /** Built-in: SMS verification code → clipboard. */
    object SmsCodeTrigger : Trigger {
        override val name = "sms_code"

        private val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
        )

        override fun evaluate(context: Context, pkg: String, title: String, text: String): Action? {
            if (!com.agent.ultra.ui.UltraPrefs.autoExtractSmsCode(context)) return null
            if (pkg !in SMS_PACKAGES && !pkg.contains("messaging") && !pkg.contains("sms")) return null
            val code = SmsCodeDetector.extract(text) ?: return null
            return Action(Action.Type.CLIPBOARD_COPY, "Code $code copied", code)
        }
    }

    /**
     * Built-in: a message that looks like a scam → a spoken/visible warning, and its
     * numbers and links held by [com.agent.ultra.gate.ScamWatch] so the gate asks
     * before anything is sent to them. Any app's notification counts: scams arrive by
     * SMS, WhatsApp, email and social apps alike.
     */
    object ScamTrigger : Trigger {
        override val name = "scam_shield"

        override fun evaluate(context: Context, pkg: String, title: String, text: String): Action? {
            if (!com.agent.ultra.ui.UltraPrefs.scamShield(context)) return null
            return check(pkg, title, text)
        }

        /** The context-free part, for tests. */
        fun check(pkg: String, title: String, text: String): Action? {
            if (pkg == "com.agent.ultra") return null          // never our own warnings
            val a = ScamSignals.assess(title, text)
            if (!a.scam) return null
            com.agent.ultra.gate.ScamWatch.remember(a, title)
            return Action(Action.Type.WARN, "This message looks like a scam: ${a.reasons}.", a.reasons)
        }
    }

    object BatteryLowTrigger : SystemTrigger {
        override val name = "battery_low"
        override val intentFilter: IntentFilter
            get() = IntentFilter(Intent.ACTION_BATTERY_LOW)

        override fun evaluate(context: Context, intent: Intent): Action? {
            if (!com.agent.ultra.ui.UltraPrefs.systemTriggers(context)) return null
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val pct = if (level > 0) "$level%" else "low"
            return Action(Action.Type.TOAST, "Battery $pct", level.toString())
        }
    }

    object BatteryOkTrigger : SystemTrigger {
        override val name = "battery_ok"
        override val intentFilter: IntentFilter
            get() = IntentFilter(Intent.ACTION_BATTERY_OKAY)

        override fun evaluate(context: Context, intent: Intent): Action? {
            if (!com.agent.ultra.ui.UltraPrefs.systemTriggers(context)) return null
            return Action(Action.Type.TOAST, "Battery recovered")
        }
    }

    object ChargingTrigger : SystemTrigger {
        override val name = "charging_state"
        override val intentFilter: IntentFilter
            get() = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }

        override fun evaluate(context: Context, intent: Intent): Action? {
            if (!com.agent.ultra.ui.UltraPrefs.systemTriggers(context)) return null
            val plugged = intent.action == Intent.ACTION_POWER_CONNECTED
            val label = if (plugged) "Charger connected" else "Charger disconnected"
            return Action(Action.Type.TOAST, label, if (plugged) "connected" else "disconnected")
        }
    }

    object ScreenTrigger : SystemTrigger {
        override val name = "screen_state"
        override val intentFilter: IntentFilter
            get() = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }

        override fun evaluate(context: Context, intent: Intent): Action? {
            if (!com.agent.ultra.ui.UltraPrefs.systemTriggers(context)) return null
            val on = intent.action == Intent.ACTION_SCREEN_ON
            return Action(Action.Type.TOAST, if (on) "Screen on" else "Screen off", if (on) "on" else "off")
        }
    }

    object HeadphoneTrigger : SystemTrigger {
        override val name = "headphones"
        override val intentFilter: IntentFilter
            get() = IntentFilter(Intent.ACTION_HEADSET_PLUG)

        override fun evaluate(context: Context, intent: Intent): Action? {
            if (!com.agent.ultra.ui.UltraPrefs.systemTriggers(context)) return null
            val plugged = intent.getIntExtra("state", 0) == 1
            val label = if (plugged) "Headphones connected" else "Headphones disconnected"
            return Action(Action.Type.TOAST, label, if (plugged) "connected" else "disconnected")
        }
    }

    object AppInstallTrigger : SystemTrigger {
        override val name = "app_install"
        override val intentFilter: IntentFilter
            get() = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }

        override fun evaluate(context: Context, intent: Intent): Action? {
            if (!com.agent.ultra.ui.UltraPrefs.systemTriggers(context)) return null
            val pkg = intent.data?.schemeSpecificPart ?: return null
            val removed = intent.action == Intent.ACTION_PACKAGE_REMOVED
            val label = if (removed) "App removed: $pkg" else "App installed: $pkg"
            return Action(Action.Type.TOAST, label, pkg)
        }
    }

    fun registerDefaults() {
        if (triggers.isEmpty()) {
            register(SmsCodeTrigger)
            register(ScamTrigger)
        }
        if (systemTriggers.isEmpty()) {
            registerSystem(BatteryLowTrigger)
            registerSystem(BatteryOkTrigger)
            registerSystem(ChargingTrigger)
            registerSystem(ScreenTrigger)
            registerSystem(HeadphoneTrigger)
            registerSystem(AppInstallTrigger)
        }
    }
}
