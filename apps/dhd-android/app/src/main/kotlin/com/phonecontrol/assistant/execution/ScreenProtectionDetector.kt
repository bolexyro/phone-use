package com.phonecontrol.assistant.execution

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.phonecontrol.assistant.domain.ScreenProtection
import com.phonecontrol.assistant.domain.ScreenProtectionStatus

private const val WINDOW_FLAG_SECURE = 0x00002000L

/** Signals extracted from WindowManager for the task currently being observed. */
internal data class WindowSecuritySignals(
    val secureWindow: Boolean = false,
    val activityNameHint: Boolean = false,
    val authenticationOverlayHint: Boolean = false,
    val signals: List<String> = emptyList(),
)

/**
 * Combine WindowManager metadata with the captured pixels.
 *
 * No single signal is treated as proof that a user must authenticate. A
 * secure flag, authentication-activity name, or a visible system biometric
 * overlay establishes that Android is protecting the task; a uniform frame
 * explains why the preview is blank.
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

    if (windowSignals.secureWindow ||
        windowSignals.activityNameHint ||
        windowSignals.authenticationOverlayHint
    ) {
        val reason = if (windowSignals.authenticationOverlayHint) {
            "Android is showing a biometric or credential prompt; DHD is waiting for the user to complete it."
        } else if (blankCapture) {
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
    var authenticationOverlayHint = false
    val signals = linkedSetOf<String>()

    for (block in windowBlocks(windowDump)) {
        val component = COMPONENT_REGEX.find(block)?.let { match ->
            val blockPackage = match.groupValues[1]
            val rawActivity = match.groupValues[2]
            blockPackage to normalizeActivityName(rawActivity, blockPackage)
        }
        val blockDisplayId = DISPLAY_ID_REGEX.find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val flags = FLAGS_REGEX.find(block)?.groupValues?.getOrNull(1)?.toLongOrNull(16)
        val secureFlag = flags != null && flags and WINDOW_FLAG_SECURE != 0L

        // BiometricPrompt is a SystemUI window, separate from the app's
        // MainActivity. It may be hosted on the default display even while a
        // task is being observed on a DHD virtual display, so do not require
        // its component to match the foreground package. The title/component
        // tokens are deliberately narrow; ordinary app windows containing
        // login text must not become an authentication gate.
        val authenticationOverlay = hasAuthenticationOverlayHint(block)
        // A secure overlay is allowed to be on another display. Samsung's
        // fingerprint service is also a trusted system package, but its FP
        // windows commonly omit FLAG_SECURE, so the package/window match is
        // the equivalent cross-display proof for that OEM path.
        val knownGlobalAuthenticationOverlay = secureFlag ||
            isSamsungFingerprintOverlay(block)
        if (isVisibleWindow(block) &&
            authenticationOverlay &&
            (blockDisplayId == null || blockDisplayId == displayId || knownGlobalAuthenticationOverlay)
        ) {
            authenticationOverlayHint = true
            signals += "authentication_overlay_hint"
        }

        val matchesDisplay = blockDisplayId == null || blockDisplayId == displayId
        val matchesTarget = component == null || (
            component.first == packageName &&
                (targetActivity == null || component.second == targetActivity)
            )
        if (!matchesDisplay || !matchesTarget) continue

        if (secureFlag) {
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
        authenticationOverlayHint = authenticationOverlayHint,
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

private fun hasAuthenticationOverlayHint(windowBlock: String): Boolean {
    val value = windowBlock.lowercase().replace(Regex("[^a-z0-9]"), "")
    if (AUTHENTICATION_OVERLAY_HINTS.any(value::contains)) return true

    // Samsung's biometric service uses short window labels such as
    // `FP Maskview` and `FP Iconview`; the owning package is stable even
    // though the labels are not descriptive enough on their own.
    return isSamsungFingerprintOverlayValue(value)
}

private fun isSamsungFingerprintOverlay(windowBlock: String): Boolean =
    isSamsungFingerprintOverlayValue(windowBlock.lowercase().replace(Regex("[^a-z0-9]"), ""))

private fun isSamsungFingerprintOverlayValue(normalizedWindowBlock: String): Boolean =
    normalizedWindowBlock.contains("comsamsungandroidbiometricsappsetting") &&
        SAMSUNG_FINGERPRINT_WINDOW_HINTS.any(normalizedWindowBlock::contains)

private fun isVisibleWindow(windowBlock: String): Boolean {
    // WindowManager keeps removed/hidden biometric windows in the dump for a
    // short time. Treat an explicit no-surface/invisible state as stale, but
    // keep accepting dumps that omit these diagnostic lines on older builds.
    val value = windowBlock.lowercase()
    return !value.contains("m hassurface=false") &&
        !value.contains("mhasurface=false") &&
        !value.contains("ison screen=false") &&
        !value.contains("isonscreen=false") &&
        !value.contains("isvisible=false") &&
        !value.contains("mviewvisibility=0x4")
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

private val AUTHENTICATION_OVERLAY_HINTS = setOf(
    // AOSP AuthContainerView uses the exact BiometricPrompt title and a
    // FLAG_SECURE SystemUI window. Samsung and older Android builds expose
    // closely related dialog/container names instead.
    "biometricprompt",
    "biometricdialog",
    "authdialog",
    "authcontainer",
    "fingerprintdialog",
    "fingerprintprompt",
    "fingerprintauthentication",
    "faceauth",
    "faceunlock",
    "udfps",
    "confirmcredential",
    "credentialdialog",
)

private val SAMSUNG_FINGERPRINT_WINDOW_HINTS = setOf(
    "fpmaskview",
    "fpiconview",
    "fptouchblockview",
    "fpguideview",
    "fpfingerprint",
)

private val WINDOW_HEADER_REGEX = Regex("^\\s*Window #\\d+\\b.*Window\\{", RegexOption.MULTILINE)
private val COMPONENT_REGEX = Regex("\\b([A-Za-z][A-Za-z0-9_.$]*)/(\\.?[A-Za-z0-9_.$]+)")
private val DISPLAY_ID_REGEX = Regex("\\b(?:mDisplayId|displayId)\\s*[=:]\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
// Samsung's `dumpsys window` prints flags as `fl=81812100` (hex without the
// 0x prefix), while AOSP/OEM variants may include `fl=0x00002000`.
private val FLAGS_REGEX = Regex("\\b(?:fl|flags)=(?:0x)?([0-9a-fA-F]+)\\b", RegexOption.IGNORE_CASE)
