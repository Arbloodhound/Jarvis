package com.agent.jarvis.listen

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeNoticeTest {
    @Test fun saysWhyListeningStopped() {
        assertEquals("Jarvis was updated", ResumeNotice.reason(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals("The phone restarted", ResumeNotice.reason(Intent.ACTION_BOOT_COMPLETED))
    }
}
