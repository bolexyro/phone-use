package com.phonecontrol.assistant.domain

/** The only action kinds that the phone runtime may eventually execute. */
enum class ActionType {
    OPEN_APP,
    TAP,
    TYPE,
    SWIPE,
    SCROLL,
    BACK,
    KEYPRESS,
    WAIT,
}

enum class KeypressKey {
    BACK,
    HOME,
    ENTER,
    DELETE,
}

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

enum class ScrollAmount {
    SMALL,
    MEDIUM,
    LARGE,
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
    init {
        require(text.isNotEmpty()) { "Type text must not be empty" }
        require(text.length <= 4096) { "Type text must be at most 4096 characters" }
    }

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

data class ScrollAction(
    val direction: ScrollDirection,
    val amount: ScrollAmount,
    override val metadata: ActionMetadata,
) : PhoneAction {
    override val type: ActionType = ActionType.SCROLL
}

data class BackAction(
    override val metadata: ActionMetadata,
) : PhoneAction {
    override val type: ActionType = ActionType.BACK
}

data class KeypressAction(
    val key: KeypressKey,
    override val metadata: ActionMetadata,
) : PhoneAction {
    override val type: ActionType = ActionType.KEYPRESS
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
    /**
     * Fingerprints for the stable regions supplied with a coordinate action.
     * The raw screenshot is deliberately not kept in the domain model; the
     * observation provider owns the capture bytes and derives these hashes.
     */
    val guardFingerprints: Map<GuardRegion, String> = emptyMap(),
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
    AGENT_MESSAGE,
    ATTENTION_REQUIRED,
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
    val targetDescription: String? = null,
)

/**
 * Convert provider-authored action metadata into a compact label that reads
 * naturally in the DHD activity stack and foreground notification. The full
 * purpose/target text remains available in the expanded activity details.
 */
fun userFacingActivityLabel(
    actionType: ActionType?,
    purpose: String,
    targetDescription: String? = null,
): String {
    val cleanPurpose = purpose.cleanActivityText()
    // Target descriptions are nouns/labels, not instructions. Do not run the
    // purpose gerund conversion over them ("search field" must not become
    // "Searching field").
    val cleanTarget = targetDescription?.cleanTargetText().orEmpty()
    val target = cleanTarget.toArticlePhrase()
    val label = when (actionType) {
        ActionType.OPEN_APP -> when {
            cleanTarget.isNotBlank() -> "Opening $cleanTarget"
            cleanPurpose.startsWith("Opening ", ignoreCase = true) -> cleanPurpose
            else -> "Opening $cleanPurpose"
        }
        ActionType.TAP -> when {
            target.isNotBlank() -> "Tapping $target"
            cleanPurpose.startsWith("Tapping ", ignoreCase = true) -> cleanPurpose
            else -> "Tapping $cleanPurpose"
        }
        ActionType.TYPE -> when {
            target.isNotBlank() -> "Entering text in $target"
            cleanPurpose.startsWith("Typing ", ignoreCase = true) -> cleanPurpose
            cleanPurpose.startsWith("Entering ", ignoreCase = true) -> cleanPurpose
            else -> "Entering text"
        }
        ActionType.SWIPE, ActionType.SCROLL -> when {
            target.isNotBlank() -> "Scrolling $target"
            cleanPurpose.startsWith("Scrolling ", ignoreCase = true) -> cleanPurpose
            else -> "Scrolling"
        }
        ActionType.BACK -> "Going back"
        ActionType.KEYPRESS -> "Pressing ${cleanTarget.ifBlank { "the key" }}"
        ActionType.WAIT -> "Waiting for the screen"
        null -> cleanPurpose
    }
    return label
        .trim()
        .replace(Regex("\\s+"), " ")
        .trimEnd('.', '!', '?')
        .replaceFirstChar { it.uppercase() }
        .take(MAX_ACTIVITY_LABEL_CHARS)
        .ifBlank { "Working on the phone" }
}

private fun String.cleanActivityText(): String {
    val normalized = trim().replace(Regex("\\s+"), " ")
    if (normalized.isBlank()) return ""
    val lower = normalized.lowercase()
    val byIndex = lower.indexOf(" by ")
    if (byIndex > 0 && lower.substringBefore(' ').let {
            it in setOf("complete", "finish", "retry", "perform", "handle")
        }) {
        val afterBy = normalized.substring(byIndex + 4).trim()
        if (afterBy.isNotBlank()) return afterBy
    }
    val firstWord = lower.substringBefore(' ')
    val gerund = mapOf(
        "complete" to "Completing",
        "finish" to "Finishing",
        "retry" to "Retrying",
        "select" to "Selecting",
        "open" to "Opening",
        "find" to "Finding",
        "check" to "Checking",
        "search" to "Searching",
        "enter" to "Entering",
        "type" to "Typing",
        "tap" to "Tapping",
        "click" to "Tapping",
        "scroll" to "Scrolling",
        "swipe" to "Scrolling",
        "wait" to "Waiting",
        "press" to "Pressing",
    )[firstWord]
    return if (gerund != null) {
        normalized.replaceFirst(Regex("(?i)^${Regex.escape(firstWord)}\\b"), gerund)
    } else {
        normalized.replaceFirstChar { it.uppercase() }
    }
}

private fun String.cleanTargetText(): String = trim().replace(Regex("\\s+"), " ")

private fun String.toArticlePhrase(): String {
    if (isBlank()) return ""
    val lower = lowercase()
    val phrase = if (lower.startsWith("the ") || lower.startsWith("a ") || lower.startsWith("an ")) {
        this
    } else {
        "the ${replaceFirstChar { it.lowercase() }}"
    }
    return phrase
}

private const val MAX_ACTIVITY_LABEL_CHARS = 80
