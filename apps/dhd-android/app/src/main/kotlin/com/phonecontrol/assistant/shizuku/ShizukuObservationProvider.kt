package com.phonecontrol.assistant.shizuku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.ObservationSnapshot
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.UUID

sealed interface ObservationCaptureResult {
    data class Succeeded(
        val snapshot: ObservationSnapshot,
        val screenshot: ByteArray,
    ) : ObservationCaptureResult

    data class Failed(val message: String) : ObservationCaptureResult
}

data class ForegroundAppInfo(
    val packageName: String,
    val activityName: String,
    val displayId: Int,
    val rotation: Int,
    val width: Int,
    val height: Int,
)

sealed interface ForegroundAppResult {
    data class Succeeded(val app: ForegroundAppInfo) : ForegroundAppResult
    data class Failed(val code: String, val message: String) : ForegroundAppResult
}

internal data class FocusedWindow(
    val packageName: String,
    val activityName: String,
)

internal fun parseFocusedWindow(text: String): FocusedWindow? {
    val match = FOCUS_REGEX.find(text) ?: return null
    val packageName = match.groupValues[1]
    val activityName = match.groupValues[2].let { raw ->
        if (raw.startsWith('.')) packageName + raw else raw
    }
    return FocusedWindow(packageName, activityName)
}

private val FOCUS_REGEX = Regex(
    "m(?:CurrentFocus|FocusedApp)=.*\\s([A-Za-z0-9_.\\$]+)/(\\.?[A-Za-z0-9_.\\$]+)",
)

/**
 * Captures the physical display using the Shizuku shell and records the
 * package/fingerprint binding needed by the policy layer.
 *
 * This is intentionally a small v0 observer. It does not claim that every
 * Samsung build exposes shell screencap or a parseable focused window; either
 * failure is returned to the caller, which decides whether an action may run or
 * whether an already-dispatched action has an unknown outcome.
 */
class ShizukuObservationProvider(
    private val context: Context,
    private val processRunner: ShizukuProcessRunner,
) {
    /**
     * Keep the compressed capture bytes beside their observation IDs so an
     * action can define guard regions after observing the screen. The bytes
     * stay provider-owned rather than becoming part of the domain snapshot.
     */
    private val screenshotLock = Any()
    private val screenshots = object : LinkedHashMap<String, ByteArray>(
        MAX_RETAINED_SCREENSHOTS + 1,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_RETAINED_SCREENSHOTS
    }

    /**
     * Read the current focused window without taking a screenshot or creating
     * an observation baseline. This is only situational context; callers must
     * still use capture() before sending any physical input.
     */
    suspend fun getForegroundApp(): ForegroundAppResult {
        val focused = when (val result = readFocusedWindowResult()) {
            is FocusedWindowReadResult.Found -> result.window
            is FocusedWindowReadResult.Failed -> {
                return ForegroundAppResult.Failed(result.code, result.message)
            }
        }
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return ForegroundAppResult.Failed(
                code = "FOREGROUND_UNAVAILABLE",
                message = "The default display could not be identified.",
            )
        val metrics = DisplayMetrics()
        runCatching { display.getRealMetrics(metrics) }.getOrElse {
            return ForegroundAppResult.Failed(
                code = "FOREGROUND_UNAVAILABLE",
                message = "The current display dimensions could not be identified.",
            )
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            return ForegroundAppResult.Failed(
                code = "FOREGROUND_UNAVAILABLE",
                message = "The current display has no usable dimensions.",
            )
        }
        return ForegroundAppResult.Succeeded(
            ForegroundAppInfo(
                packageName = focused.packageName,
                activityName = focused.activityName,
                displayId = Display.DEFAULT_DISPLAY,
                rotation = display.rotation,
                width = metrics.widthPixels,
                height = metrics.heightPixels,
            ),
        )
    }

    suspend fun capture(
        expectedPackageName: String? = null,
        guardRegions: List<GuardRegion> = emptyList(),
    ): ObservationCaptureResult {
        val screenshotResult = processRunner.run(listOf("screencap", "-p"))
        if (screenshotResult.timedOut || screenshotResult.exitCode != 0) {
            return ObservationCaptureResult.Failed(
                "Shizuku screencap failed: ${screenshotResult.stderr.ifBlank { "exit ${screenshotResult.exitCode}" }}",
            )
        }
        val screenshot = screenshotResult.stdout
        val bounds = decodeBounds(screenshot)
            ?: return ObservationCaptureResult.Failed("Shizuku returned an invalid PNG screenshot.")
        if (bounds.first <= 0 || bounds.second <= 0) {
            return ObservationCaptureResult.Failed("The screenshot has no usable display dimensions.")
        }
        val focused = readFocusedWindow()
        val packageName = focused?.packageName ?: expectedPackageName
        if (packageName == null) {
            return ObservationCaptureResult.Failed(
                "The foreground package could not be identified; refusing to bind an action.",
            )
        }
        if (expectedPackageName != null && packageName != expectedPackageName) {
            return ObservationCaptureResult.Failed(
                "The foreground app changed to $packageName; expected $expectedPackageName.",
            )
        }

        val guardFingerprints = try {
            fingerprintGuards(screenshot, bounds.first, bounds.second, guardRegions)
        } catch (error: IllegalArgumentException) {
            return ObservationCaptureResult.Failed(error.message ?: "Invalid guard region.")
        }
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: SurfaceRotation.ROTATION_0
        val snapshot = ObservationSnapshot(
            id = UUID.randomUUID().toString(),
            packageName = packageName,
            activityName = focused?.activityName,
            displayId = Display.DEFAULT_DISPLAY,
            rotation = rotation,
            width = bounds.first,
            height = bounds.second,
            screenshotFingerprint = sha256(screenshot),
            guardFingerprints = guardFingerprints,
        )
        synchronized(screenshotLock) {
            screenshots[snapshot.id] = screenshot.copyOf()
        }
        return ObservationCaptureResult.Succeeded(snapshot, screenshot)
    }

    /** Return the raw capture for a previously issued observation, if retained. */
    internal fun screenshotFor(snapshot: ObservationSnapshot): ByteArray? = synchronized(screenshotLock) {
        screenshots[snapshot.id]?.copyOf()
    }

    private suspend fun readFocusedWindow(): FocusedWindow? = when (val result = readFocusedWindowResult()) {
        is FocusedWindowReadResult.Found -> result.window
        is FocusedWindowReadResult.Failed -> null
    }

    private suspend fun readFocusedWindowResult(): FocusedWindowReadResult {
        // One UI's `dumpsys window windows` omits the focus summary. The
        // top-level `window` dump includes mCurrentFocus/mFocusedApp while
        // retaining the same shell permission boundary.
        val result = processRunner.run(listOf("dumpsys", "window"))
        if (result.timedOut || result.exitCode != 0) {
            val detail = result.stderr.ifBlank { "exit ${result.exitCode}" }
            return FocusedWindowReadResult.Failed(
                code = foregroundFailureCode(detail),
                message = "Could not read the current foreground app through Shizuku: $detail",
            )
        }
        val text = result.stdout.toString(Charsets.UTF_8)
        return parseFocusedWindow(text)?.let(FocusedWindowReadResult::Found)
            ?: FocusedWindowReadResult.Failed(
                code = "FOREGROUND_UNAVAILABLE",
                message = "The current foreground app could not be identified.",
            )
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }

    internal fun fingerprintGuards(
        bytes: ByteArray,
        width: Int,
        height: Int,
        regions: List<GuardRegion>,
    ): Map<GuardRegion, String> {
        if (regions.isEmpty()) return emptyMap()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("The screenshot could not be decoded for guard comparison.")
        return try {
            regions.associateWith { region ->
                require(region.right <= width && region.bottom <= height) {
                    "Guard region ${region.left},${region.top},${region.right},${region.bottom} exceeds the screenshot bounds ${width}x${height}."
                }
                fingerprintRegion(bitmap, region)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun fingerprintRegion(bitmap: Bitmap, region: GuardRegion): String {
        val regionWidth = region.right - region.left
        val regionHeight = region.bottom - region.top
        val pixels = IntArray(regionWidth * regionHeight)
        bitmap.getPixels(
            pixels,
            0,
            regionWidth,
            region.left,
            region.top,
            regionWidth,
            regionHeight,
        )
        val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
        pixels.forEach(bytes::putInt)
        return sha256(bytes.array())
    }

    private sealed interface FocusedWindowReadResult {
        data class Found(val window: FocusedWindow) : FocusedWindowReadResult
        data class Failed(val code: String, val message: String) : FocusedWindowReadResult
    }

    private companion object {
        private const val MAX_RETAINED_SCREENSHOTS = 64

        private fun foregroundFailureCode(detail: String): String = when {
            detail.contains("Shizuku is unavailable", ignoreCase = true) -> "SHIZUKU_UNAVAILABLE"
            detail.contains("permission is required", ignoreCase = true) -> "SHIZUKU_PERMISSION_REQUIRED"
            else -> "FOREGROUND_UNAVAILABLE"
        }

        fun sha256(bytes: ByteArray): String = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

private object SurfaceRotation {
    const val ROTATION_0 = 0
}
