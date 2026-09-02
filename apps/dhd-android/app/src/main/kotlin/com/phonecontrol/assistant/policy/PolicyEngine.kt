package com.phonecontrol.assistant.policy

import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.TapAction

data class PolicyContext(
    val enabledPackages: Set<String>,
    val foregroundPackage: String?,
    val currentObservationId: String?,
    val fullAccess: Boolean = false,
)

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision

    data class Denied(
        val code: DenialCode,
        val message: String,
    ) : PolicyDecision
}

enum class DenialCode {
    INVALID_ACTION_METADATA,
    INVALID_GUARD_REGIONS,
    APP_NOT_ALLOWED,
    NO_FOREGROUND_PACKAGE,
    OBSERVATION_MISSING,
    STALE_OBSERVATION,
}

/**
 * The phone-side policy authority. A model-provided flag cannot grant itself
 * permission: this engine derives the allowlist and execution decision from
 * phone state and the typed action only.
 */
class PolicyEngine(
    /**
     * Structural observation freshness is the default safety check. Optional
     * guard-region fingerprints provide the stricter visual check for actions
     * that need a particular part of the screen to remain unchanged.
     */
    private val enforceObservationFreshness: Boolean = true,
) {
    fun evaluate(action: PhoneAction, context: PolicyContext): PolicyDecision {
        val metadataError = validateMetadata(action.metadata)
        if (metadataError != null) {
            return PolicyDecision.Denied(DenialCode.INVALID_ACTION_METADATA, metadataError)
        }
        if (
            enforceObservationFreshness &&
            action is TapAction &&
            action.metadata.guardRegions.isNotEmpty() &&
            action.metadata.guardRegions.none { it.contains(action.x, action.y) }
        ) {
            return PolicyDecision.Denied(
                DenialCode.INVALID_GUARD_REGIONS,
                "The tap coordinate must be contained by at least one guard region.",
            )
        }

        if (!context.fullAccess) {
            when (action) {
                is OpenAppAction -> {
                    if (action.packageName !in context.enabledPackages) {
                        return PolicyDecision.Denied(
                            DenialCode.APP_NOT_ALLOWED,
                            "The app ${action.packageName} is not enabled for Phone Control.",
                        )
                    }
                }

                else -> {
                    val foregroundPackage = context.foregroundPackage
                        ?: return PolicyDecision.Denied(
                            DenialCode.NO_FOREGROUND_PACKAGE,
                            "No foreground app is available for this action.",
                        )
                    if (foregroundPackage !in context.enabledPackages) {
                        return PolicyDecision.Denied(
                            DenialCode.APP_NOT_ALLOWED,
                            "The foreground app is not enabled for Phone Control.",
                        )
                    }
                }
            }
        }

        if (enforceObservationFreshness) {
            val currentObservationId = context.currentObservationId
                ?: return PolicyDecision.Denied(
                    DenialCode.OBSERVATION_MISSING,
                    "A fresh observation is required before an action can run.",
                )
            if (currentObservationId != action.metadata.observationId) {
                return PolicyDecision.Denied(
                    DenialCode.STALE_OBSERVATION,
                    "The action was proposed from an older observation.",
                )
            }
        }

        // DHD v0 intentionally has no action-level confirmation gate. The
        // phone still enforces the allowlist, foreground ownership, metadata,
        // and (when enabled) observation-freshness checks above.
        return PolicyDecision.Allowed
    }

    private fun validateMetadata(metadata: ActionMetadata): String? {
        if (metadata.purpose.isBlank()) return "Every action needs a user-facing purpose."
        if (enforceObservationFreshness && metadata.observationId.isBlank()) {
            return "Every action needs an observation ID."
        }
        if (metadata.targetDescription.isBlank()) return "Every action needs a target description."
        return null
    }
}
