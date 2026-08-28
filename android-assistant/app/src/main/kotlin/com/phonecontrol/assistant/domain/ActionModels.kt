package com.phonecontrol.assistant.domain

/** The only action kinds that the phone runtime may eventually execute. */
enum class ActionType {
    OPEN_APP,
    TAP,
    TYPE,
    SWIPE,
    BACK,
    WAIT,
}

/**
 * A rectangular patch of the last observation that must remain stable before
 * a coordinate action can be dispatched. Coordinates are screenshot pixels.
 */
data class GuardRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0) { "Guard region left must be non-negative" }
        require(top >= 0) { "Guard region top must be non-negative" }
        require(right > left) { "Guard region right must be greater than left" }
        require(bottom > top) { "Guard region bottom must be greater than top" }
    }

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

/** User-visible explanation and provenance attached to every proposed action. */
data class ActionMetadata(
    val purpose: String,
    val observationId: String,
    val targetDescription: String,
    val guardRegions: List<GuardRegion> = emptyList(),
)

/**
 * Typed action envelope. No raw shell command or provider-specific payload is
 * part of this contract; the phone owns validation and execution.
 */
sealed interface PhoneAction {
    val type: ActionType
    val metadata: ActionMetadata
}

data class OpenAppAction(
    val packageName: String,
    override val metadata: ActionMetadata,
) : PhoneAction {
    override val type: ActionType = ActionType.OPEN_APP
}

data class TapAction(
    val x: Int,
    val y: Int,
    override val metadata: ActionMetadata,
) : PhoneAction {
    init {
        require(x >= 0) { "Tap x must be non-negative" }
        require(y >= 0) { "Tap y must be non-negative" }
    }

    override val type: ActionType = ActionType.TAP
}

data class TypeAction(
    val text: String,
    override val metadata: ActionMetadata,
) : PhoneAction {
    override val type: ActionType = ActionType.TYPE
}

data class SwipeAction(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long = 350,
    override val metadata: ActionMetadata,
) : PhoneAction {
    init {
        require(startX >= 0 && startY >= 0) { "Swipe start coordinates must be non-negative" }
        require(endX >= 0 && endY >= 0) { "Swipe end coordinates must be non-negative" }
        require(durationMs in 1..10_000) { "Swipe duration must be between 1 and 10000 ms" }
    }

    override val type: ActionType = ActionType.SWIPE
}

data class BackAction(
    override val metadata: ActionMetadata,
) : PhoneAction {
    override val type: ActionType = ActionType.BACK
}

data class WaitAction(
    val durationMs: Long,
    override val metadata: ActionMetadata,
) : PhoneAction {
    init {
        require(durationMs in 1..30_000) { "Wait duration must be between 1 and 30000 ms" }
    }

    override val type: ActionType = ActionType.WAIT
}

/** Minimal provenance for the observation used to propose an action. */
data class ObservationSnapshot(
    val id: String,
    val packageName: String,
    val activityName: String?,
    val displayId: Int,
    val rotation: Int,
    val width: Int,
    val height: Int,
    val screenshotFingerprint: String,
)

enum class ActivityEventKind {
    SESSION_STARTED,
    SESSION_PAUSED,
    SESSION_RESUMED,
    SESSION_STOPPED,
    SESSION_COMPLETED,
    ACTION_PROPOSED,
    ACTION_STARTED,
    ACTION_SUCCEEDED,
    ACTION_FAILED,
    CONFIRMATION_REQUIRED,
    SYSTEM,
}

/** Safe, user-facing activity item. Sensitive payloads must not be copied here. */
data class ActivityEvent(
    val id: String,
    val sessionId: String?,
    val timestampEpochMs: Long,
    val kind: ActivityEventKind,
    val message: String,
    val actionType: ActionType? = null,
    val purpose: String? = null,
    val observationId: String? = null,
)
