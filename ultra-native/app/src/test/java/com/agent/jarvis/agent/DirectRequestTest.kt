package com.agent.jarvis.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRequestTest {
    private val contacts = listOf("Mom", "Sarah Connor", "Dr. Ahmed Khan")
    private fun skip(req: String, word: String, screen: String = "") =
        DirectRequest.skipConfirmation(req, word, screen, contacts)

    @Test fun messageToKnownContactNamedAndShown() = assertTrue(skip("text Sarah I'm running late", "send", "Sarah Connor  I'm running late"))
    @Test fun messageToKnownContactNotOnScreen() = assertFalse(skip("text Sarah I'm late", "send", "John Smith  I'm late"))
    @Test fun messageToSomeoneNew() = assertFalse(skip("text Bob I'm late", "send", "Bob  I'm late"))
    @Test fun messageNotAskedFor() = assertFalse(skip("open my messages", "send", "Sarah Connor"))
    @Test fun shortContactName() = assertTrue(skip("message mom hello", "send", "Mom hello"))
    @Test fun moneyAlwaysAsks() = assertFalse(skip("pay the electricity bill", "pay"))
    @Test fun buyAlwaysAsks() = assertFalse(skip("buy the cheapest charger", "buy"))
    @Test fun bookingAlwaysAsks() = assertFalse(skip("book a table for two", "book"))
    @Test fun deleteAlwaysAsks() = assertFalse(skip("delete the old photos", "delete"))
    @Test fun termsAlwaysAsk() = assertFalse(skip("accept the terms", "accept"))
    @Test fun unreadableAlwaysAsks() = assertFalse(skip("send it", ActionGate.UNREADABLE))
    @Test fun postWhenAsked() = assertTrue(skip("post this photo to mastodon", "post", "New post"))
    @Test fun postWithPriceOnScreenAsks() = assertFalse(skip("post my ad", "post", "Promote for €4.99"))
    @Test fun installWhenAsked() = assertTrue(skip("install firefox", "install", "Firefox Install"))
    @Test fun confirmOnlyWhenSaid() = assertFalse(skip("set an alarm", "confirm"))
    @Test fun confirmWhenSaid() = assertTrue(skip("confirm the appointment", "confirm", "Appointment Tuesday"))
    @Test fun unknownWordAsks() = assertFalse(skip("do it", "submit"))
    @Test fun emptyRequestAsks() = assertFalse(skip("", "send", "Mom"))
}
