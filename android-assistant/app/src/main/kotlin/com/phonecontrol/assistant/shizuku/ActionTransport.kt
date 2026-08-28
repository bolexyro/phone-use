package com.phonecontrol.assistant.shizuku

import android.content.Context
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.TapAction

sealed interface TransportResult {
    data class Rejected(val code: RejectionCode, val message: String) : TransportResult
    data class Unsupported(val message: String) : TransportResult
    data class Succeeded(val message: String) : TransportResult
}

enum class RejectionCode {
    SHIZUKU_UNAVAILABLE,
    SHIZUKU_PERMISSION_REQUIRED,
    OBSERVATION_MISSING,
    STALE_OBSERVATION,
    FOREGROUND_CHANGED,
    INVALID_COORDINATE,
    COMMAND_FAILED,
}

interface PhoneActionTransport {
    suspend fun execute(action: PhoneAction, observation: ObservationSnapshot?): TransportResult
}

/** Shizuku-backed executor for the first two typed actions. */
class ShizukuActionTransport(
    private val controller: ShizukuController,
    private val context: Context,
    private val observationProvider: ShizukuObservationProvider,
    private val processRunner: ShizukuProcessRunner,
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
                "A fresh observation is required; the action was not executed.",
            )
        }
        if (observation.id != action.metadata.observationId) {
            return TransportResult.Rejected(
                RejectionCode.STALE_OBSERVATION,
                "The observation is stale; the action was not executed.",
            )
        }

        return when (action) {
            is OpenAppAction -> openApp(action)
            is TapAction -> tap(action, observation)
            else -> TransportResult.Unsupported(
                "${action.type} is not wired to input injection yet; no phone action was executed.",
            )
        }
    }

    private suspend fun openApp(action: OpenAppAction): TransportResult {
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
        )
    }

    private suspend fun tap(
        action: TapAction,
        observation: ObservationSnapshot,
    ): TransportResult {
        val fresh = observationProvider.capture(
            expectedPackageName = observation.packageName,
            guardRegions = action.metadata.guardRegions,
        )
        val current = when (fresh) {
            is ObservationCaptureResult.Failed -> {
                return TransportResult.Rejected(
                    RejectionCode.FOREGROUND_CHANGED,
                    fresh.message,
                )
            }

            is ObservationCaptureResult.Succeeded -> fresh.snapshot
        }
        if (isStale(observation, current, action.metadata.guardRegions)) {
            return TransportResult.Rejected(
                RejectionCode.STALE_OBSERVATION,
                "The approved screen changed before the tap; no input was sent.",
            )
        }
        if (action.x >= current.width || action.y >= current.height) {
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
        )
    }

    private fun isStale(
        previous: ObservationSnapshot,
        current: ObservationSnapshot,
        guardRegions: List<GuardRegion>,
    ): Boolean {
        if (
            previous.packageName != current.packageName ||
            previous.activityName != current.activityName ||
            previous.displayId != current.displayId ||
            previous.rotation != current.rotation ||
            previous.width != current.width ||
            previous.height != current.height
        ) {
            return true
        }
        if (guardRegions.isEmpty()) {
            return previous.screenshotFingerprint != current.screenshotFingerprint
        }
        return guardRegions.any { region ->
            val before = previous.guardFingerprints[region]
            val after = current.guardFingerprints[region]
            before == null || after == null || before != after
        }
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
}
