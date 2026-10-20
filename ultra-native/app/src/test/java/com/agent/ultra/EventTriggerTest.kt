package com.agent.ultra

import android.content.Intent
import com.agent.ultra.agent.EventTrigger
import com.agent.ultra.agent.EventTrigger.Action
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventTriggerTest {

    @Before
    fun reset() {
        for (name in listOf("triggers", "systemTriggers", "receivers")) {
            val field = EventTrigger::class.java.getDeclaredField(name)
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(EventTrigger) as MutableList<*>).clear()
        }
        val started = EventTrigger::class.java.getDeclaredField("systemStarted")
        started.isAccessible = true
        started.setBoolean(EventTrigger, false)
    }

    private fun fieldCount(name: String): Int {
        val field = EventTrigger::class.java.getDeclaredField(name)
        field.isAccessible = true
        return (field.get(EventTrigger) as List<*>).size
    }

    private fun ctx(): android.content.Context = android.content.ContextWrapper(null)

    // ── Notification triggers ────────────────────────────────────────

    @Test
    fun `no triggers returns empty`() {
        val actions = EventTrigger.evaluate(ctx(), "com.test.app", "Title", "Body text")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `custom trigger fires`() {
        EventTrigger.register(object : EventTrigger.Trigger {
            override val name = "test_trigger"
            override fun evaluate(
                context: android.content.Context,
                pkg: String, title: String, text: String,
            ): Action? =
                if (text.contains("fire")) Action(Action.Type.TOAST, "Fired!")
                else null
        })
        val actions = EventTrigger.evaluate(ctx(), "com.test", "", "please fire now")
        assertEquals(1, actions.size)
        assertEquals(Action.Type.TOAST, actions[0].type)
        assertEquals("Fired!", actions[0].label)
    }

    @Test
    fun `custom trigger skips when no match`() {
        EventTrigger.register(object : EventTrigger.Trigger {
            override val name = "test_trigger"
            override fun evaluate(
                context: android.content.Context,
                pkg: String, title: String, text: String,
            ): Action? = null
        })
        val actions = EventTrigger.evaluate(ctx(), "com.test", "", "nothing here")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `multiple triggers can fire`() {
        EventTrigger.register(object : EventTrigger.Trigger {
            override val name = "a"
            override fun evaluate(
                context: android.content.Context,
                pkg: String, title: String, text: String,
            ) = Action(Action.Type.TOAST, "A")
        })
        EventTrigger.register(object : EventTrigger.Trigger {
            override val name = "b"
            override fun evaluate(
                context: android.content.Context,
                pkg: String, title: String, text: String,
            ) = Action(Action.Type.CLIPBOARD_COPY, "B", "data")
        })
        val actions = EventTrigger.evaluate(ctx(), "com.test", "", "")
        assertEquals(2, actions.size)
        assertEquals("A", actions[0].label)
        assertEquals("B", actions[1].label)
        assertEquals("data", actions[1].data)
    }

    @Test
    fun `failing trigger does not break others`() {
        EventTrigger.register(object : EventTrigger.Trigger {
            override val name = "boom"
            override fun evaluate(
                context: android.content.Context,
                pkg: String, title: String, text: String,
            ): Action = throw RuntimeException("kaboom")
        })
        EventTrigger.register(object : EventTrigger.Trigger {
            override val name = "ok"
            override fun evaluate(
                context: android.content.Context,
                pkg: String, title: String, text: String,
            ) = Action(Action.Type.TOAST, "survived")
        })
        val actions = EventTrigger.evaluate(ctx(), "com.test", "", "")
        assertEquals(1, actions.size)
        assertEquals("survived", actions[0].label)
    }

    @Test
    fun `registerDefaults adds notification and system triggers once`() {
        EventTrigger.registerDefaults()
        EventTrigger.registerDefaults()
        assertEquals(2, fieldCount("triggers"))     // sms_code + scam_shield
        assertEquals(6, fieldCount("systemTriggers"))
    }

    @Test
    fun `action data defaults to empty`() {
        val a = Action(Action.Type.TOAST, "hello")
        assertEquals("", a.data)
    }

    // ── System trigger registration ──────────────────────────────────

    @Test
    fun `custom system trigger registers`() {
        EventTrigger.registerSystem(object : EventTrigger.SystemTrigger {
            override val name = "test_sys"
            override val intentFilter get() = android.content.IntentFilter("test.action")
            override fun evaluate(context: android.content.Context, intent: Intent): Action? =
                Action(Action.Type.TOAST, "sys fired")
        })
        assertEquals(1, fieldCount("systemTriggers"))
    }

    @Test
    fun `all system trigger names are distinct`() {
        EventTrigger.registerDefaults()
        val names = listOf(
            EventTrigger.BatteryLowTrigger.name,
            EventTrigger.BatteryOkTrigger.name,
            EventTrigger.ChargingTrigger.name,
            EventTrigger.ScreenTrigger.name,
            EventTrigger.HeadphoneTrigger.name,
            EventTrigger.AppInstallTrigger.name,
        )
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `registerDefaults registers all 6 system triggers`() {
        EventTrigger.registerDefaults()
        assertEquals(6, fieldCount("systemTriggers"))
    }

    @Test
    fun `battery low trigger has correct name`() {
        assertEquals("battery_low", EventTrigger.BatteryLowTrigger.name)
    }

    @Test
    fun `battery ok trigger has correct name`() {
        assertEquals("battery_ok", EventTrigger.BatteryOkTrigger.name)
    }

    @Test
    fun `charging trigger has correct name`() {
        assertEquals("charging_state", EventTrigger.ChargingTrigger.name)
    }

    @Test
    fun `screen trigger has correct name`() {
        assertEquals("screen_state", EventTrigger.ScreenTrigger.name)
    }

    @Test
    fun `headphone trigger has correct name`() {
        assertEquals("headphones", EventTrigger.HeadphoneTrigger.name)
    }

    @Test
    fun `app install trigger has correct name`() {
        assertEquals("app_install", EventTrigger.AppInstallTrigger.name)
    }

    @Test
    fun `charging trigger data reflects intent action`() {
        val connected = Action(Action.Type.TOAST, "Charger connected", "connected")
        val disconnected = Action(Action.Type.TOAST, "Charger disconnected", "disconnected")
        assertEquals("connected", connected.data)
        assertEquals("disconnected", disconnected.data)
        assertNotEquals(connected.label, disconnected.label)
    }

    @Test
    fun `headphone trigger data reflects state`() {
        val plugged = Action(Action.Type.TOAST, "Headphones connected", "connected")
        val unplugged = Action(Action.Type.TOAST, "Headphones disconnected", "disconnected")
        assertEquals("connected", plugged.data)
        assertEquals("disconnected", unplugged.data)
        assertNotEquals(plugged.label, unplugged.label)
    }

    @Test
    fun `app install trigger data carries package name`() {
        val install = Action(Action.Type.TOAST, "App installed: com.example.app", "com.example.app")
        val remove = Action(Action.Type.TOAST, "App removed: com.example.app", "com.example.app")
        assertEquals("com.example.app", install.data)
        assertEquals("com.example.app", remove.data)
        assertNotEquals(install.label, remove.label)
    }
}
