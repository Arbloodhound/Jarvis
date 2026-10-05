package com.agent.ultra

import com.agent.ultra.agent.Tools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tools a secret may pass through.
 *
 * Written after an agent, refused permission to text a one-time code it had
 * just read, wrote the code to the clipboard and then to a note file. Both
 * succeeded. The guard covered the exits someone had thought of.
 */
class SecretEgressTest {

    @Test
    fun `the ways out are not on the list`() {
        // Every one of these moves data somewhere it can be read again — the
        // clipboard by any app, a note by anyone with the phone, a message by
        // whoever receives it.
        for (exit in listOf(
            "sms_send", "clipboard_write", "note_create",
            "app_launch", "open_url", "web_search",
        )) {
            assertFalse("$exit must be checked", exit in Tools.SECRET_MAY_PASS)
        }
    }

    @Test
    fun `reads are exempt because they carry nothing outward`() {
        for (read in listOf(
            "read_text_on_screen", "read_screen_deep", "describe_screen",
            "battery_status", "device_info", "notification_read",
        )) {
            assertTrue("$read is a read", read in Tools.SECRET_MAY_PASS)
        }
    }

    @Test
    fun `navigation is exempt because it checks itself more strictly`() {
        // react_navigate may type a code back into the app it came from, and
        // nowhere else. That is the normal case of entering a code in the app
        // that sent it, and blocking it centrally would make the agent useless
        // for the task people most want.
        assertTrue("react_navigate" in Tools.SECRET_MAY_PASS)
    }

    @Test
    fun `a tool nobody has written yet is guarded by default`() {
        // The whole point of an allow-list. A future share_to_whatsapp or
        // upload_file is refused until someone deliberately exempts it.
        for (future in listOf("share_sheet", "upload_file", "email_send", "print")) {
            assertFalse(future in Tools.SECRET_MAY_PASS)
        }
    }
}
