package com.phonecontrol.assistant.execution

import com.phonecontrol.assistant.domain.ScreenProtectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneObservationProviderTest {
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

    @Test
    fun `detects secure authentication window for the observed display`() {
        val signals = parseWindowSecuritySignals(
            windowDump = """
                Window #0 Window{abc u0 team.opay.pay.home/team.opay.pay.home.PinAppLockActivity}
                  mDisplayId=21
                  mAttrs={(0,0)(fillxfill) fl=0x00002000}
            """.trimIndent(),
            displayId = 21,
            packageName = "team.opay.pay.home",
            activityName = "team.opay.pay.home.PinAppLockActivity",
        )

        assertTrue(signals.secureWindow)
        assertTrue(signals.activityNameHint)
        assertTrue(signals.signals.contains("window_flag_secure"))
        assertTrue(signals.signals.contains("authentication_activity_hint"))
    }

    @Test
    fun `ignores a secure window belonging to another display`() {
        val signals = parseWindowSecuritySignals(
            windowDump = """
                Window #0 Window{abc u0 team.opay.pay.home/team.opay.pay.home.PinAppLockActivity}
                  mDisplayId=22
                  mAttrs={(0,0)(fillxfill) fl=0x00002000}
            """.trimIndent(),
            displayId = 21,
            packageName = "team.opay.pay.home",
            activityName = "team.opay.pay.home.HomeActivity",
        )

        assertTrue(!signals.secureWindow)
        assertTrue(!signals.activityNameHint)
    }

    @Test
    fun `secure signal marks the observation as requiring user attention`() {
        val protection = detectScreenProtection(
            screenshot = ByteArray(0),
            windowDump = """
                Window #0 Window{abc u0 team.opay.pay.home/team.opay.pay.home.PinAppLockActivity}
                  mDisplayId=21
                  mAttrs={(0,0)(fillxfill) fl=0x00002000}
            """.trimIndent(),
            displayId = 21,
            packageName = "team.opay.pay.home",
            activityName = "team.opay.pay.home.PinAppLockActivity",
        )

        assertEquals(ScreenProtectionStatus.SECURE, protection.status)
        assertTrue(protection.requiresUserAttention)
    }
}
