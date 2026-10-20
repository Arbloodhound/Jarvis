package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class UserNotesTest {
    @Test fun commands() {
        assertEquals("Buy milk", UserNotes.command("note: buy milk"))
        assertEquals("The gate code is 4512", UserNotes.command("Remember that the gate code is 4512."))
        assertEquals("Call mum on Sunday", UserNotes.command("take a note call mum on Sunday"))
        assertEquals("Pick up the parcel", UserNotes.command("jot down pick up the parcel"))
        for (s in listOf("remember that time we went to Rome?", "remember that?", "what notes do I have",
                "do you remember that Sam called", "note"))
            assertNull(s, UserNotes.command(s))
    }

    private val zone = ZoneId.of("Europe/London")
    private val now = LocalDateTime.of(2026, 10, 7, 10, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun kinds() {
        assertEquals(NoteKind.TODO, UserNotes.build("Buy milk", now, zone).kind)
        assertEquals(NoteKind.TODO, UserNotes.build("I need to renew my passport", now, zone).kind)
        assertEquals(NoteKind.FACT, UserNotes.build("The gate code is 4512", now, zone).kind)
        val plan = UserNotes.build("Dentist tomorrow at 3pm", now, zone)
        assertEquals(NoteKind.PLAN, plan.kind)
        assertNotNull(plan.whenTs)
        assertEquals(emptyList<Long>(), plan.sourceIds)   // made by the owner, not heard
    }
}
