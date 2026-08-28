package com.phonecontrol.assistant.shizuku

import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction

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
}

interface PhoneActionTransport {
    suspend fun execute(action: PhoneAction, observation: ObservationSnapshot?): TransportResult
}

/**
 * Shizuku-backed boundary for future Android input operations.
 *
 * The capability check is real, but every action currently fails closed after
 * it. This prevents a UI prototype from implying that S23 input injection has
 * been validated. No raw command string crosses this interface.
 */
class ShizukuActionTransport(
    private val controller: ShizukuController,
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

        return TransportResult.Unsupported(
            "${action.type} is not wired to input injection yet; no phone action was executed.",
        )
    }
}
