package com.phonecontrol.assistant.policy

import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.domain.TypeAction
import java.util.Locale

/** Categories that must be confirmed immediately before execution. */
enum class SensitiveActionCategory {
    SEND,
    PURCHASE,
    TRANSFER,
    DELETE,
    SUBMIT,
}

data class PolicyContext(
    val enabledPackages: Set<String>,
    val foregroundPackage: String?,
    val currentObservationId: String?,
    /** A phone-owned screen/workflow assessment. Model metadata cannot clear it. */
    val phoneDetectedSensitiveCategory: SensitiveActionCategory? = null,
)

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision

    data class RequiresConfirmation(
        val category: SensitiveActionCategory,
        val message: String,
    ) : PolicyDecision

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
 * permission: this engine derives the allowlist and confirmation decision from
 * phone state and the typed action only.
 */
class PolicyEngine(
    private val sensitiveActionClassifier: SensitiveActionClassifier = KeywordSensitiveActionClassifier,
    /**
     * Screenshot/observation freshness is intentionally off for the first
     * phone-assistant prototype. Keep the switch so the guard layer can be
     * re-enabled without changing the policy API when its false-positive
     * behavior is ready.
     */
    private val enforceObservationFreshness: Boolean = false,
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

        // Provider-authored purpose/target text may conservatively add a
        // confirmation. It can never suppress a category detected locally
        // from the phone's current screen or trusted workflow state.
        val sensitiveCategory = context.phoneDetectedSensitiveCategory
            ?: sensitiveActionClassifier.classify(action)
        return if (sensitiveCategory == null) {
            PolicyDecision.Allowed
        } else {
            PolicyDecision.RequiresConfirmation(
                category = sensitiveCategory,
                message = "Confirm ${sensitiveCategory.userLabel.lowercase(Locale.US)} before continuing.",
            )
        }
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

interface SensitiveActionClassifier {
    fun classify(action: PhoneAction): SensitiveActionCategory?
}

/** Conservative starter classifier; a trusted executor can replace it later. */
object KeywordSensitiveActionClassifier : SensitiveActionClassifier {
    private val transferWords = setOf("transfer", "withdraw", "send money", "bank transfer")
    private val purchaseWords = setOf("purchase", "buy", "checkout", "pay", "place order", "order")
    private val deleteWords = setOf("delete", "remove", "erase", "trash", "permanently")
    private val submitWords = setOf("submit", "confirm", "book", "reserve", "publish")
    private val sendWords = setOf("send", "sending", "message", "post")

    override fun classify(action: PhoneAction): SensitiveActionCategory? {
        val metadata = action.metadata
        val searchable = buildString {
            append(metadata.purpose)
            append(' ')
            append(metadata.targetDescription)
            if (action is TypeAction) {
                // Never log this text; it is used only for the local policy check.
                append(' ')
                append(action.text)
            }
        }.lowercase(Locale.US)

        return when {
            containsAny(searchable, transferWords) -> SensitiveActionCategory.TRANSFER
            containsAny(searchable, purchaseWords) -> SensitiveActionCategory.PURCHASE
            containsAny(searchable, deleteWords) -> SensitiveActionCategory.DELETE
            containsAny(searchable, submitWords) -> SensitiveActionCategory.SUBMIT
            containsAny(searchable, sendWords) -> SensitiveActionCategory.SEND
            else -> null
        }
    }

    private fun containsAny(value: String, words: Set<String>): Boolean =
        words.any { word ->
            if (' ' in word) word in value else Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(value)
        }
}

private val SensitiveActionCategory.userLabel: String
    get() = name.replace('_', ' ')
