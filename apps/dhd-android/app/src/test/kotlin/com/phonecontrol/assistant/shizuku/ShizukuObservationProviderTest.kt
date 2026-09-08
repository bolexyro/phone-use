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
    fun `parses Samsung window flags without a hexadecimal prefix`() {
        val signals = parseWindowSecuritySignals(
            windowDump = """
                Window #0 Window{abc u0 team.opay.pay.home/team.opay.pay.home.PinAppLockActivity}
                  mDisplayId=21
                  mAttrs={(0,0)(fillxfill) fl=00002000}
            """.trimIndent(),
            displayId = 21,
            packageName = "team.opay.pay.home",
            activityName = "team.opay.pay.home.PinAppLockActivity",
        )

        assertTrue(signals.secureWindow)
        assertTrue(signals.signals.contains("window_flag_secure"))
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

    @Test
    fun `detects biometric prompt overlay even when app activity is still focused`() {
        val signals = parseWindowSecuritySignals(
            windowDump = """
                Window #0 Window{abc u0 com.android.systemui/com.android.systemui.BiometricPrompt}
                  mDisplayId=45
                  mAttrs={(0,0)(fillxfill) fl=0x00002000}
                  mTitle=BiometricPrompt
                Window #1 Window{def u0 com.moniepoint.personal/com.moniepoint.personal.MainActivity}
                  mDisplayId=45
            """.trimIndent(),
            displayId = 45,
            packageName = "com.moniepoint.personal",
            activityName = "com.moniepoint.personal.MainActivity",
        )

        assertTrue(signals.authenticationOverlayHint)
        assertTrue(signals.signals.contains("authentication_overlay_hint"))
        assertTrue(!signals.activityNameHint)
    }

    @Test
    fun `biometric prompt overlay requires attention even when capture is not blank`() {
        val protection = detectScreenProtection(
            screenshot = ByteArray(0),
            windowDump = """
                Window #0 Window{abc u0 com.android.systemui/com.android.systemui.BiometricPrompt}
                  mDisplayId=45
                  mAttrs={(0,0)(fillxfill) fl=0x00002000}
                  mTitle=BiometricPrompt
            """.trimIndent(),
            displayId = 45,
            packageName = "com.moniepoint.personal",
            activityName = "com.moniepoint.personal.MainActivity",
        )

        assertEquals(ScreenProtectionStatus.SECURE, protection.status)
        assertTrue(protection.requiresUserAttention)
        assertTrue(protection.reason?.contains("biometric", ignoreCase = true) == true)
    }

    @Test
    fun `does not treat a biometric overlay on another display as task evidence without secure flag`() {
        val signals = parseWindowSecuritySignals(
            windowDump = """
                Window #0 Window{abc u0 com.android.systemui/com.android.systemui.BiometricPrompt}
                  mDisplayId=0
                  mAttrs={(0,0)(fillxfill) fl=0x00000000}
                  mTitle=BiometricPrompt
            """.trimIndent(),
            displayId = 45,
            packageName = "com.moniepoint.personal",
            activityName = "com.moniepoint.personal.MainActivity",
        )

        assertTrue(!signals.authenticationOverlayHint)
    }

    @Test
    fun `detects an active Samsung fingerprint overlay by its system package`() {
        val signals = parseWindowSecuritySignals(
            windowDump = """
                Window #0 Window{abc u0 FP Iconview}:
                  mDisplayId=0
                  package=com.samsung.android.biometrics.app.setting appop=NONE
                  mAttrs={(0,0)(fillxfill) fl=1000018}
                  mHasSurface=true isReadyForDisplay()=true
                  isOnScreen=true
                  isVisible=true
            """.trimIndent(),
            displayId = 45,
            packageName = "com.moniepoint.personal",
            activityName = "com.moniepoint.personal.MainActivity",
        )

        assertTrue(signals.authenticationOverlayHint)
        assertTrue(signals.signals.contains("authentication_overlay_hint"))
    }

    @Test
    fun `ignores a removed Samsung fingerprint overlay`() {
        val signals = parseWindowSecuritySignals(
            windowDump = """
                Window #0 Window{abc u0 FP Iconview}:
                  mDisplayId=0
                  package=com.samsung.android.biometrics.app.setting appop=NONE
                  mAttrs={(0,0)(fillxfill) fl=10002000}
                  mHasSurface=false isReadyForDisplay()=false
                  isOnScreen=false
                  isVisible=false
            """.trimIndent(),
            displayId = 45,
            packageName = "com.moniepoint.personal",
            activityName = "com.moniepoint.personal.MainActivity",
        )

        assertTrue(!signals.authenticationOverlayHint)
    }
}
