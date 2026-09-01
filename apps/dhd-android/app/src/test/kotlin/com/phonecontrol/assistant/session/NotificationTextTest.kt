package com.phonecontrol.assistant.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    @Test
    fun `uses the first useful sentence and removes simple markdown`() {
        assertEquals(
            "The order was placed successfully.",
            completionNotificationPreview("## Done\n- **The order was placed successfully.**\n\nMore details follow."),
        )
    }

    @Test
    fun `falls back when the final message is unavailable`() {
        assertEquals(
            DEFAULT_COMPLETION_NOTIFICATION_PREVIEW,
            completionNotificationPreview(" \n```\nprivate details\n```\n"),
        )
    }

    @Test
    fun `bounds a long first sentence`() {
        val preview = completionNotificationPreview("A ".repeat(200) + "finished.")

        assertTrue(preview.length <= 160)
        assertTrue(preview.endsWith("…"))
        assertEquals('A', preview.first())
    }
}
