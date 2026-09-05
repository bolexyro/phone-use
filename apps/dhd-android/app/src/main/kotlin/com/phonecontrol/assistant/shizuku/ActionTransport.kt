package com.phonecontrol.assistant.shizuku

import android.content.Context
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.ObservationSize
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.StaleObservationDiagnostics
import com.phonecontrol.assistant.domain.StaleObservationReason
import com.phonecontrol.assistant.domain.StaleObservationReasonCode
import com.phonecontrol.assistant.domain.BackAction
import com.phonecontrol.assistant.domain.KeypressAction
import com.phonecontrol.assistant.domain.KeypressKey
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.ScrollAction
import com.phonecontrol.assistant.domain.ScrollAmount
import com.phonecontrol.assistant.domain.ScrollDirection
import com.phonecontrol.assistant.domain.SwipeAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.domain.TypeAction
import com.phonecontrol.assistant.domain.WaitAction
import kotlinx.coroutines.delay

sealed interface TransportResult {
    data class Rejected(
        val code: RejectionCode,
        val message: String,
        val details: StaleObservationDiagnostics? = null,
    ) : TransportResult
    data class Unsupported(val message: String) : TransportResult
    data class Succeeded(
        val message: String,
        /**
         * The exact screenshot captured by the freshness check immediately
         * before input dispatch. This is diagnostic metadata for the desktop
         * companion and is never part of the model-facing action result.
         */
        val beforeScreenshot: ByteArray? = null,
    ) : TransportResult
}

enum class RejectionCode {
    SHIZUKU_UNAVAILABLE,
    SHIZUKU_PERMISSION_REQUIRED,
    OBSERVATION_MISSING,
    OBSERVATION_FAILED,
    STALE_OBSERVATION,
    FOREGROUND_CHANGED,
    INVALID_COORDINATE,
    UNSUPPORTED_TEXT,
    COMMAND_FAILED,
}

interface PhoneActionTransport {
    suspend fun execute(action: PhoneAction, observation: ObservationSnapshot?): TransportResult
}

/** Shizuku-backed executor for the typed v0 action set. */
class ShizukuActionTransport(
    private val controller: ShizukuController,
    private val context: Context,
    private val observationProvider: ShizukuObservationProvider,
    private val processRunner: ShizukuProcessRunner,
    /**
     * Structural observation freshness is always enabled in production. An
     * action's guard regions opt into the stricter visual comparison.
     */
    private val enforceObservationFreshness: Boolean = true,
) : PhoneActionTransport {
    override suspend fun execute(
        action: PhoneAction,
        observation: ObservationSnapshot?,
    ): TransportResult {
        val status = controller.status.value
        if (!status.binderAvailable) {
            return TransportResult.Rejected(
                RejectionCode.SHIZUKU_UNAVAILABLE,
                "Shizuku is unavailable; the action was not executed.",
            )
        }
        if (!status.permissionGranted) {
            return TransportResult.Rejected(
                RejectionCode.SHIZUKU_PERMISSION_REQUIRED,
                "Shizuku permission is required; the action was not executed.",
            )
        }
        if (observation == null) {
            return TransportResult.Rejected(
                RejectionCode.OBSERVATION_MISSING,
                "A current phone screenshot is required for display bounds; the action was not executed.",
            )
        }
        if (enforceObservationFreshness && observation.id != action.metadata.observationId) {
            return TransportResult.Rejected(
                RejectionCode.STALE_OBSERVATION,
                "The observation is stale; the action was not executed.",
                staleObservationDiagnostics(
                    approvedObservationId = action.metadata.observationId,
                    currentObservationId = observation.id,
                    reasons = listOf(
                        StaleObservationReason(
                            code = StaleObservationReasonCode.OBSERVATION_REPLACED,
                            approved = action.metadata.observationId,
                            current = observation.id,
                        ),
                    ),
                ),
            )
        }

        return when (action) {
            is OpenAppAction -> openApp(action, observation)
            is TapAction -> tap(action, observation)
            is TypeAction -> type(action, observation)
            is SwipeAction -> swipe(action, observation)
            is ScrollAction -> scroll(action, observation)
            is BackAction -> back(action, observation)
            is KeypressAction -> keypress(action, observation)
            is WaitAction -> wait(action, observation)
        }
    }

    private suspend fun openApp(
        action: OpenAppAction,
        observation: ObservationSnapshot,
    ): TransportResult {
        val before = when (val check = freshCheck(action, observation)) {
            is FreshCheck.Rejected -> return check.result
            is FreshCheck.Ready -> check
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(action.packageName)
            ?: return TransportResult.Rejected(
                RejectionCode.COMMAND_FAILED,
                "No launchable activity was found for ${action.packageName}.",
            )
        val component = launchIntent.component
            ?: return TransportResult.Rejected(
                RejectionCode.COMMAND_FAILED,
                "The launch intent for ${action.packageName} has no explicit component.",
            )
        if (component.packageName != action.packageName) {
            return TransportResult.Rejected(
                RejectionCode.COMMAND_FAILED,
                "The launch intent resolved outside the requested package.",
            )
        }

        val result = processRunner.run(
            listOf(
                "am",
                "start",
                "-W",
                "-n",
                "${component.packageName}/${component.className}",
            ),
        )
        return commandResult(
            result,
            successMessage = "Opened ${action.packageName}.",
        ).withBeforeScreenshot(before.screenshot)
    }

    private suspend fun tap(
        action: TapAction,
        observation: ObservationSnapshot,
    ): TransportResult {
        val before = when (val check = freshCheck(action, observation)) {
            is FreshCheck.Rejected -> return check.result
            is FreshCheck.Ready -> check
        }
        val current = before.snapshot
        if (!current.contains(action.x, action.y)) {
            return TransportResult.Rejected(
                RejectionCode.INVALID_COORDINATE,
                "Tap coordinate ${action.x},${action.y} is outside the ${current.width}x${current.height} display.",
            )
        }

        val result = processRunner.run(
            listOf("input", "tap", action.x.toString(), action.y.toString()),
        )
        return commandResult(
            result,
            successMessage = "Tapped ${action.x},${action.y}: ${action.metadata.purpose}",
        ).withBeforeScreenshot(before.screenshot)
    }

    private suspend fun type(
        action: TypeAction,
        observation: ObservationSnapshot,
    ): TransportResult {
        val before = when (val check = freshCheck(action, observation)) {
            is FreshCheck.Rejected -> return check.result
            is FreshCheck.Ready -> check
        }
        val encoded = try {
            encodeInputText(action.text)
        } catch (error: IllegalArgumentException) {
            return TransportResult.Rejected(
                RejectionCode.UNSUPPORTED_TEXT,
                error.message ?: "The requested text cannot be sent by Android input text.",
            )
        }
        val result = processRunner.run(listOf("input", "text", encoded))
        return commandResult(
            result,
            successMessage = "Typed ${action.text.length} characters: ${action.metadata.purpose}",
        ).withBeforeScreenshot(before.screenshot)
    }

    private suspend fun swipe(
        action: SwipeAction,
        observation: ObservationSnapshot,
    ): TransportResult {
        val before = when (val check = freshCheck(action, observation)) {
            is FreshCheck.Rejected -> return check.result
            is FreshCheck.Ready -> check
        }
        val current = before.snapshot
        if (!current.contains(action.startX, action.startY) || !current.contains(action.endX, action.endY)) {
            return TransportResult.Rejected(
                RejectionCode.INVALID_COORDINATE,
                "Swipe coordinates are outside the ${current.width}x${current.height} display.",
            )
        }
        val result = processRunner.run(
            listOf(
                "input",
                "swipe",
                action.startX.toString(),
                action.startY.toString(),
                action.endX.toString(),
                action.endY.toString(),
                action.durationMs.toString(),
            ),
        )
        return commandResult(
            result,
            successMessage = "Swiped from ${action.startX},${action.startY} to ${action.endX},${action.endY}: ${action.metadata.purpose}",
        ).withBeforeScreenshot(before.screenshot)
    }

    private suspend fun back(
        action: BackAction,
        observation: ObservationSnapshot,
    ): TransportResult {
        return keypress(
            KeypressAction(KeypressKey.BACK, action.metadata),
            observation,
            displayName = "Pressed Back",
        )
    }

    private suspend fun keypress(
        action: KeypressAction,
        observation: ObservationSnapshot,
        displayName: String = "Pressed ${action.key.name}",
    ): TransportResult {
        val before = when (val check = freshCheck(action, observation)) {
            is FreshCheck.Rejected -> return check.result
            is FreshCheck.Ready -> check
        }
        val keyCode = when (action.key) {
            KeypressKey.BACK -> "KEYCODE_BACK"
            KeypressKey.HOME -> "KEYCODE_HOME"
            KeypressKey.ENTER -> "KEYCODE_ENTER"
            KeypressKey.DELETE -> "KEYCODE_DEL"
        }
        val result = processRunner.run(listOf("input", "keyevent", keyCode))
        return commandResult(result, successMessage = "$displayName: ${action.metadata.purpose}")
            .withBeforeScreenshot(before.screenshot)
    }

    private suspend fun scroll(
        action: ScrollAction,
        observation: ObservationSnapshot,
    ): TransportResult {
        val before = when (val check = freshCheck(action, observation)) {
            is FreshCheck.Rejected -> return check.result
            is FreshCheck.Ready -> check
        }
        val current = before.snapshot
        val gesture = scrollGesture(current, action.direction, action.amount)
        val result = processRunner.run(
            listOf(
                "input",
                "swipe",
                gesture.startX.toString(),
                gesture.startY.toString(),
                gesture.endX.toString(),
                gesture.endY.toString(),
                SCROLL_DURATION_MS.toString(),
            ),
        )
        return commandResult(
            result,
            successMessage = "Scrolled ${action.direction.name.lowercase()} (${action.amount.name.lowercase()}): ${action.metadata.purpose}",
        ).withBeforeScreenshot(before.screenshot)
    }

    private suspend fun wait(
        action: WaitAction,
        observation: ObservationSnapshot,
    ): TransportResult {
        val before = when (val check = freshCheck(action, observation)) {
            is FreshCheck.Rejected -> return check.result
            is FreshCheck.Ready -> check
        }
        delay(action.durationMs)
        return TransportResult.Succeeded(
            "Waited ${action.durationMs} ms: ${action.metadata.purpose}",
            beforeScreenshot = before.screenshot.copyOf(),
        )
    }

    private suspend fun freshCheck(
        action: PhoneAction,
        observation: ObservationSnapshot,
    ): FreshCheck {
        val guardRegions = if (enforceObservationFreshness) {
            action.metadata.guardRegions
        } else {
            emptyList()
        }
        val fresh = observationProvider.capture(
            // Capture the actual current screen. The structural comparison
            // below decides whether it is still the screen the agent observed;
            // the observer must not discard it just because the package changed.
            expectedPackageName = null,
            guardRegions = guardRegions,
        )
        val current = when (fresh) {
            is ObservationCaptureResult.Failed -> {
                return FreshCheck.Rejected(
                    TransportResult.Rejected(
                        RejectionCode.OBSERVATION_FAILED,
                        "Could not capture a fresh pre-action observation; the action was not executed: ${fresh.message}",
                    ),
                )
            }

            is ObservationCaptureResult.Succeeded -> fresh.snapshot
        }
        if (enforceObservationFreshness) {
            // Guard regions are chosen with the action, after the model has
            // inspected the preceding observation. Recompute their baseline
            // fingerprints from that retained screenshot instead of relying
            // on the observation having been captured with guards already.
            val baseline = baselineForGuards(observation, guardRegions)
            if (!isObservationStale(baseline, current, guardRegions)) {
                return FreshCheck.Ready(current, fresh.screenshot)
            }
            return FreshCheck.Rejected(
                TransportResult.Rejected(
                    RejectionCode.STALE_OBSERVATION,
                    "The approved screen changed before ${action.type.name.lowercase().replace('_', ' ')}; no input was sent.",
                    staleObservationDiagnostics(
                        approvedObservationId = observation.id,
                        currentObservationId = current.id,
                        reasons = observationStaleReasons(baseline, current, guardRegions),
                    ),
                ),
            )
        }
        return FreshCheck.Ready(current, fresh.screenshot)
    }

    private fun baselineForGuards(
        observation: ObservationSnapshot,
        guardRegions: List<GuardRegion>,
    ): ObservationSnapshot {
        if (guardRegions.isEmpty()) return observation
        val screenshot = observationProvider.screenshotFor(observation) ?: return observation
        return observation.copy(
            guardFingerprints = observationProvider.fingerprintGuards(
                screenshot,
                observation.width,
                observation.height,
                guardRegions,
            ),
        )
    }

    private fun commandResult(
        result: ShizukuProcessResult,
        successMessage: String,
    ): TransportResult {
        if (result.timedOut || result.exitCode == null || result.exitCode != 0) {
            val detail = result.stderr.ifBlank { "exit ${result.exitCode}" }
            return TransportResult.Rejected(
                RejectionCode.COMMAND_FAILED,
                "Shizuku command failed: $detail",
            )
        }
        return TransportResult.Succeeded(successMessage)
    }

    private sealed interface FreshCheck {
        data class Ready(
            val snapshot: ObservationSnapshot,
            val screenshot: ByteArray,
        ) : FreshCheck
        data class Rejected(val result: TransportResult.Rejected) : FreshCheck
    }

    private fun TransportResult.withBeforeScreenshot(screenshot: ByteArray): TransportResult =
        when (this) {
            is TransportResult.Succeeded -> copy(beforeScreenshot = screenshot.copyOf())
            else -> this
        }

    private fun ObservationSnapshot.contains(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height

    private fun encodeInputText(text: String): String {
        require(text.none { it == '%' }) {
            "Android input text cannot safely encode '%' in this v0 transport."
        }
        require(text.none { it.code < 0x20 || it.code == 0x7f }) {
            "Android input text does not accept control characters."
        }
        // `input text` uses `%s` as its documented space escape. The argv is
        // passed directly through Shizuku, so shell quoting is neither needed
        // nor permitted here.
        return text.replace(" ", "%s")
    }

    private fun scrollGesture(
        observation: ObservationSnapshot,
        direction: ScrollDirection,
        amount: ScrollAmount,
    ): Gesture {
        val distance = when (amount) {
            ScrollAmount.SMALL -> 0.22f
            ScrollAmount.MEDIUM -> 0.42f
            ScrollAmount.LARGE -> 0.62f
        }
        val centerX = observation.width / 2
        val centerY = observation.height / 2
        val horizontalDistance = (observation.width * distance).toInt().coerceAtLeast(1)
        val verticalDistance = (observation.height * distance).toInt().coerceAtLeast(1)
        return when (direction) {
            ScrollDirection.UP -> Gesture(
                centerX,
                (centerY + verticalDistance / 2).coerceAtMost(observation.height - 1),
                centerX,
                (centerY - verticalDistance / 2).coerceAtLeast(0),
            )

            ScrollDirection.DOWN -> Gesture(
                centerX,
                (centerY - verticalDistance / 2).coerceAtLeast(0),
                centerX,
                (centerY + verticalDistance / 2).coerceAtMost(observation.height - 1),
            )

            ScrollDirection.LEFT -> Gesture(
                (centerX + horizontalDistance / 2).coerceAtMost(observation.width - 1),
                centerY,
                (centerX - horizontalDistance / 2).coerceAtLeast(0),
                centerY,
            )

            ScrollDirection.RIGHT -> Gesture(
                (centerX - horizontalDistance / 2).coerceAtLeast(0),
                centerY,
                (centerX + horizontalDistance / 2).coerceAtMost(observation.width - 1),
                centerY,
            )
        }
    }

    private data class Gesture(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
    )

    private companion object {
        const val SCROLL_DURATION_MS = 400L
    }
}

/**
 * Returns whether a fresh observation is no longer safe to use for an action.
 * Structural fields are always compared; guard-region fingerprints opt into
 * the stricter visual comparison without requiring the entire screenshot to
 * remain identical.
 */
internal fun isObservationStale(
    previous: ObservationSnapshot,
    current: ObservationSnapshot,
    guardRegions: List<GuardRegion>,
): Boolean {
    return observationStaleReasons(previous, current, guardRegions).isNotEmpty()
}

/**
 * Explain the same freshness comparison used by [isObservationStale]. The
 * screenshot fingerprint is intentionally not compared here: visual changes
 * are only safety-significant when they occur inside an explicitly supplied
 * guard region.
 */
internal fun observationStaleReasons(
    previous: ObservationSnapshot,
    current: ObservationSnapshot,
    guardRegions: List<GuardRegion>,
): List<StaleObservationReason> {
    val reasons = mutableListOf<StaleObservationReason>()
    if (previous.packageName != current.packageName) {
        reasons += StaleObservationReason(
            code = StaleObservationReasonCode.PACKAGE_CHANGED,
            approved = previous.packageName,
            current = current.packageName,
        )
    }
    if (previous.activityName != current.activityName) {
        reasons += StaleObservationReason(
            code = StaleObservationReasonCode.ACTIVITY_CHANGED,
            approved = previous.activityName,
            current = current.activityName,
        )
    }
    if (previous.displayId != current.displayId) {
        reasons += StaleObservationReason(
            code = StaleObservationReasonCode.DISPLAY_CHANGED,
            approved = previous.displayId,
            current = current.displayId,
        )
    }
    if (previous.rotation != current.rotation) {
        reasons += StaleObservationReason(
            code = StaleObservationReasonCode.ROTATION_CHANGED,
            approved = previous.rotation,
            current = current.rotation,
        )
    }
    if (previous.width != current.width || previous.height != current.height) {
        reasons += StaleObservationReason(
            code = StaleObservationReasonCode.DISPLAY_SIZE_CHANGED,
            approved = ObservationSize(previous.width, previous.height),
            current = ObservationSize(current.width, current.height),
        )
    }
    guardRegions.forEach { region ->
        val before = previous.guardFingerprints[region]
        val after = current.guardFingerprints[region]
        if (before == null || after == null || before != after) {
            reasons += StaleObservationReason(
                code = StaleObservationReasonCode.GUARD_REGION_CHANGED,
                approved = before,
                current = after,
                guardRegion = region,
            )
        }
    }
    return reasons
}

private fun staleObservationDiagnostics(
    approvedObservationId: String,
    currentObservationId: String?,
    reasons: List<StaleObservationReason>,
): StaleObservationDiagnostics = StaleObservationDiagnostics(
    approvedObservationId = approvedObservationId,
    currentObservationId = currentObservationId,
    reasons = reasons.ifEmpty {
        listOf(
            StaleObservationReason(
                code = StaleObservationReasonCode.OBSERVATION_REPLACED,
                approved = approvedObservationId,
                current = currentObservationId,
            ),
        )
    },
)
