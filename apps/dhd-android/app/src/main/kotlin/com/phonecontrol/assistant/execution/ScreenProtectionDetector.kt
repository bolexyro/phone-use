package com.phonecontrol.assistant.execution

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.phonecontrol.assistant.domain.ScreenProtection
import com.phonecontrol.assistant.domain.ScreenProtectionStatus

private const val WINDOW_FLAG_SECURE = 0x00002000L

/** Signals extracted from WindowManager for the display currently being observed. */
internal data class WindowSecuritySignals(
    val secureWindow: Boolean = false,
    val activityNameHint: Boolean = false,
    val signals: List<String> = emptyList(),
)

/**
 * Combine WindowManager metadata with the captured pixels.
 *
 * No single signal is treated as proof that a user must authenticate. A
 * secure flag or an authentication-activity name establishes that Android is
 * protecting the window; a uniform frame explains why the preview is blank.
 * If the frame is blank but no protection signal is available, the result is
 * deliberately BLANK_UNKNOWN so the agent can re-observe rather than claim a
 * biometric prompt that DHD could not verify.
 */
internal fun detectScreenProtection(
    screenshot: ByteArray,
    windowDump: String,
    displayId: Int,
    packageName: String,
    activityName: String?,
): ScreenProtection {
    val windowSignals = parseWindowSecuritySignals(
        windowDump = windowDump,
        displayId = displayId,
        packageName = packageName,
        activityName = activityName,
    )
    val blankCapture = isUniformCapture(screenshot)
    val signals = buildList {
        addAll(windowSignals.signals)
        if (blankCapture) add("uniform_capture")
    }.distinct()

    if (windowSignals.secureWindow || windowSignals.activityNameHint) {
        val reason = if (blankCapture) {
            "The task display is blank because the focused screen is protected by Android or the app."
        } else {
            "The focused task window is protected; DHD will not guess at hidden or authentication input."
        }
        return ScreenProtection(
            status = ScreenProtectionStatus.SECURE,
            requiresUserAttention = true,
            signals = signals,
            reason = reason,
        )
    }

    if (blankCapture) {
        return ScreenProtection(
            status = ScreenProtectionStatus.BLANK_UNKNOWN,
            requiresUserAttention = false,
            signals = signals,
            reason = "The task display capture is uniform, but DHD could not prove that security caused it.",
        )
    }

    return ScreenProtection.VISIBLE
}

/** Parse the human-readable WindowManager dump without relying on OEM text beyond stable tokens. */
internal fun parseWindowSecuritySignals(
    windowDump: String,
    displayId: Int,
    packageName: String,
    activityName: String?,
): WindowSecuritySignals {
    val targetActivity = activityName?.let(::normalizeActivityName)
    var secureWindow = false
    var activityNameHint = hasAuthenticationActivityHint(activityName)
    val signals = linkedSetOf<String>()

    for (block in windowBlocks(windowDump)) {
        val component = COMPONENT_REGEX.find(block)?.let { match ->
            val blockPackage = match.groupValues[1]
            val rawActivity = match.groupValues[2]
            blockPackage to normalizeActivityName(rawActivity, blockPackage)
        }
        val blockDisplayId = DISPLAY_ID_REGEX.find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val matchesDisplay = blockDisplayId == null || blockDisplayId == displayId
        val matchesTarget = component == null || (
            component.first == packageName &&
                (targetActivity == null || component.second == targetActivity)
            )
        if (!matchesDisplay || !matchesTarget) continue

        val flags = FLAGS_REGEX.find(block)?.groupValues?.getOrNull(1)?.toLongOrNull(16)
        if (flags != null && flags and WINDOW_FLAG_SECURE != 0L) {
            secureWindow = true
            signals += "window_flag_secure"
        }
        if (component?.second?.let(::hasAuthenticationActivityHint) == true) {
            activityNameHint = true
            signals += "authentication_activity_hint"
        }
    }

    // Some OEM dumps omit per-window blocks for a focused app. The activity
    // name remains useful as a conservative secondary signal in that case.
    if (hasAuthenticationActivityHint(activityName)) {
        activityNameHint = true
        signals += "authentication_activity_hint"
    }

    return WindowSecuritySignals(
        secureWindow = secureWindow,
        activityNameHint = activityNameHint,
        signals = signals.toList(),
    )
}

private fun windowBlocks(dump: String): Sequence<String> = sequence {
    var current: StringBuilder? = null
    for (line in dump.lineSequence()) {
        if (WINDOW_HEADER_REGEX.containsMatchIn(line)) {
            current?.let { yield(it.toString()) }
            current = StringBuilder()
        }
        current?.append(line)?.append('\n')
    }
    current?.let { yield(it.toString()) }
}

private fun isUniformCapture(screenshot: ByteArray): Boolean {
    val bitmap = runCatching {
        BitmapFactory.decodeByteArray(screenshot, 0, screenshot.size)
    }.getOrNull() ?: return false
    return try {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val stepX = maxOf(1, bitmap.width / 32)
        val stepY = maxOf(1, bitmap.height / 32)
        val first = bitmap.getPixel(0, 0)
        val firstRed = (first shr 16) and 0xff
        val firstGreen = (first shr 8) and 0xff
        val firstBlue = first and 0xff
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                if (kotlin.math.abs(((pixel shr 16) and 0xff) - firstRed) > 2 ||
                    kotlin.math.abs(((pixel shr 8) and 0xff) - firstGreen) > 2 ||
                    kotlin.math.abs((pixel and 0xff) - firstBlue) > 2
                ) {
                    return false
                }
            }
        }
        true
    } finally {
        bitmap.recycle()
    }
}

private fun normalizeActivityName(raw: String, packageName: String? = null): String {
    val clean = raw.trim()
    return if (clean.startsWith('.') && packageName != null) packageName + clean else clean
}

private fun hasAuthenticationActivityHint(activityName: String?): Boolean {
    val value = activityName?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: return false
    return AUTHENTICATION_ACTIVITY_HINTS.any(value::contains)
}

private val AUTHENTICATION_ACTIVITY_HINTS = setOf(
    "pinapplock",
    "biometric",
    "fingerprint",
    "faceunlock",
    "confirmcredential",
    "devicecredential",
    "keyguard",
    "passcode",
    "password",
    "unlock",
)

private val WINDOW_HEADER_REGEX = Regex("^\\s*Window #\\d+\\b.*Window\\{", RegexOption.MULTILINE)
private val COMPONENT_REGEX = Regex("\\b([A-Za-z][A-Za-z0-9_.$]*)/(\\.?[A-Za-z0-9_.$]+)")
private val DISPLAY_ID_REGEX = Regex("\\b(?:mDisplayId|displayId)\\s*[=:]\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
private val FLAGS_REGEX = Regex("\\b(?:fl|flags)=0x([0-9a-fA-F]+)\\b", RegexOption.IGNORE_CASE)
