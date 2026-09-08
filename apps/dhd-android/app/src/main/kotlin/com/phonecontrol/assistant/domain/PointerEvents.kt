package com.phonecontrol.assistant.domain

/**
 * A gesture that the task-display preview can render as visual feedback.
 *
 * These events are presentation metadata only. They never grant the preview
 * an input path and are emitted only after the corresponding phone command
 * has completed successfully.
 */
sealed interface TaskPointerEvent {
    val sequence: Long
    val sessionId: String
    val displayWidth: Int
    val displayHeight: Int

    data class Click(
        override val sequence: Long,
        override val sessionId: String,
        val x: Int,
        val y: Int,
        override val displayWidth: Int,
        override val displayHeight: Int,
    ) : TaskPointerEvent

    data class Swipe(
        override val sequence: Long,
        override val sessionId: String,
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long,
        override val displayWidth: Int,
        override val displayHeight: Int,
    ) : TaskPointerEvent

    data class Scroll(
        override val sequence: Long,
        override val sessionId: String,
        val direction: ScrollDirection,
        val amount: ScrollAmount,
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long,
        override val displayWidth: Int,
        override val displayHeight: Int,
    ) : TaskPointerEvent
}

const val TASK_SCROLL_DURATION_MS = 400L

/** Coordinates used by both the scroll executor and the preview overlay. */
data class TaskScrollGesture(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
)

/**
 * Reproduce the gesture sent by the typed Android transport. Keeping this in
 * the domain layer makes the preview path use the same display geometry as
 * the command path instead of maintaining a second approximation.
 */
fun calculateTaskScrollGesture(
    width: Int,
    height: Int,
    direction: ScrollDirection,
    amount: ScrollAmount,
    centerX: Int? = null,
    centerY: Int? = null,
): TaskScrollGesture {
    require(width > 0) { "Task display width must be positive." }
    require(height > 0) { "Task display height must be positive." }
    require((centerX == null) == (centerY == null)) {
        "Scroll center x and y must be provided together."
    }
    require(centerX == null || centerX in 0 until width) {
        "Scroll center x must be inside the task display."
    }
    require(centerY == null || centerY in 0 until height) {
        "Scroll center y must be inside the task display."
    }
    val distance = when (amount) {
        ScrollAmount.SMALL -> 0.22f
        ScrollAmount.MEDIUM -> 0.42f
        ScrollAmount.LARGE -> 0.62f
    }
    val resolvedCenterX = centerX ?: width / 2
    val resolvedCenterY = centerY ?: height / 2
    val horizontalDistance = (width * distance).toInt().coerceAtLeast(1)
    val verticalDistance = (height * distance).toInt().coerceAtLeast(1)
    return when (direction) {
        ScrollDirection.UP -> TaskScrollGesture(
            resolvedCenterX,
            (resolvedCenterY + verticalDistance / 2).coerceAtMost(height - 1),
            resolvedCenterX,
            (resolvedCenterY - verticalDistance / 2).coerceAtLeast(0),
        )

        ScrollDirection.DOWN -> TaskScrollGesture(
            resolvedCenterX,
            (resolvedCenterY - verticalDistance / 2).coerceAtLeast(0),
            resolvedCenterX,
            (resolvedCenterY + verticalDistance / 2).coerceAtMost(height - 1),
        )

        ScrollDirection.LEFT -> TaskScrollGesture(
            (resolvedCenterX + horizontalDistance / 2).coerceAtMost(width - 1),
            resolvedCenterY,
            (resolvedCenterX - horizontalDistance / 2).coerceAtLeast(0),
            resolvedCenterY,
        )

        ScrollDirection.RIGHT -> TaskScrollGesture(
            (resolvedCenterX - horizontalDistance / 2).coerceAtLeast(0),
            resolvedCenterY,
            (resolvedCenterX + horizontalDistance / 2).coerceAtMost(width - 1),
            resolvedCenterY,
        )
    }
}
