package com.phonecontrol.assistant.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShizukuObservationProviderTest {
    @Test
    fun `parses current focus package and expands relative activity`() {
        val focused = parseFocusedWindow(
            "mCurrentFocus=Window{123 u0 com.spotify/.MainActivity}",
        )

        assertEquals("com.spotify", focused?.packageName)
        assertEquals("com.spotify.MainActivity", focused?.activityName)
    }

    @Test
    fun `parses focused app when current focus is absent`() {
        val focused = parseFocusedWindow(
            "mFocusedApp=ActivityRecord{456 u0 com.example.mail/com.example.mail.ComposeActivity}",
        )

        assertEquals("com.example.mail", focused?.packageName)
        assertEquals("com.example.mail.ComposeActivity", focused?.activityName)
    }

    @Test
    fun `returns no foreground app when the dump has no supported focus line`() {
        assertNull(parseFocusedWindow("mCurrentFocus=Window{null}"))
    }
}
