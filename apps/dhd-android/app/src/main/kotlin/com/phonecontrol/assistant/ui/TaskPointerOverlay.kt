package com.phonecontrol.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.domain.ScrollDirection
import com.phonecontrol.assistant.domain.TaskPointerEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

private enum class CursorMode {
    ARROW,
    MOUSE,
}

private data class GestureVisual(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long,
    val direction: ScrollDirection?,
    val label: String,
    val icon: String,
    val displayWidth: Int,
    val displayHeight: Int,
)

/**
 * Read-only visual feedback for the task display. This is deliberately a
 * Compose sibling of the decoder TextureView: it never receives pointer
 * input, changes the decoded frame, or turns the preview into a control path.
 */
@Composable
internal fun TaskPointerOverlay(
    event: TaskPointerEvent?,
    modifier: Modifier = Modifier,
) {
    val cursorX = remember { Animatable(0f) }
    val cursorY = remember { Animatable(0f) }
    val clickPulse = remember { Animatable(0f) }
    val clickRotation = remember { Animatable(0f) }
    val trackAlpha = remember { Animatable(0f) }
    var cursorMode by remember { mutableStateOf<CursorMode?>(null) }
    var gestureEvent by remember { mutableStateOf<TaskPointerEvent?>(null) }
    var showBadge by remember { mutableStateOf(false) }
    var hasCursor by remember { mutableStateOf(false) }

    LaunchedEffect(event?.sequence) {
        val pointer = event
        if (pointer == null) {
            cursorMode = null
            gestureEvent = null
            showBadge = false
            trackAlpha.snapTo(0f)
            return@LaunchedEffect
        }

        when (pointer) {
            is TaskPointerEvent.Click -> {
                cursorMode = CursorMode.ARROW
                gestureEvent = null
                showBadge = false
                trackAlpha.snapTo(0f)
                moveCursor(
                    cursorX = cursorX,
                    cursorY = cursorY,
                    target = mapDisplayPoint(
                        pointer.x,
                        pointer.y,
                        pointer.displayWidth,
                        pointer.displayHeight,
                    ),
                    hasCursor = hasCursor,
                )
                hasCursor = true
                clickPulse.snapTo(0f)
                clickRotation.snapTo(0f)
                kotlinx.coroutines.coroutineScope {
                    launch {
                        clickPulse.animateTo(1f, tween(480, easing = LinearEasing))
                    }
                    launch {
                        clickRotation.animateTo(
                            targetValue = 0f,
                            animationSpec = keyframes {
                                durationMillis = 480
                                32f at 135
                                32f at 265
                                -4f at 390
                                0f at 480
                            },
                        )
                    }
                }
                clickPulse.snapTo(0f)
                clickRotation.snapTo(0f)
            }

            is TaskPointerEvent.Scroll,
            is TaskPointerEvent.Swipe -> {
                cursorMode = CursorMode.MOUSE
                gestureEvent = pointer
                showBadge = true
                trackAlpha.snapTo(1f)
                val visual = pointer.toGestureVisual()
                moveCursor(
                    cursorX = cursorX,
                    cursorY = cursorY,
                    target = mapDisplayPoint(
                        (visual.startX + visual.endX) / 2,
                        (visual.startY + visual.endY) / 2,
                        visual.displayWidth,
                        visual.displayHeight,
                    ),
                    hasCursor = hasCursor,
                )
                hasCursor = true
                delay(max(150L, visual.durationMs))
                trackAlpha.animateTo(0f, tween(280, easing = LinearEasing))
                showBadge = false
            }
        }
    }

    val wheelTransition = rememberInfiniteTransition(label = "task-pointer-wheel")
    val wheelProgress by wheelTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "task-pointer-wheel-progress",
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pointer = event
            val gesture = gestureEvent?.toGestureVisual()
            if (gesture != null) {
                drawGestureTrack(gesture, trackAlpha.value)
                drawMouse(
                    center = Offset(cursorX.value * size.width, cursorY.value * size.height),
                    direction = gesture.direction,
                    wheelProgress = wheelProgress,
                )
            } else if (cursorMode == CursorMode.ARROW && pointer is TaskPointerEvent.Click) {
                drawArrow(
                    point = Offset(cursorX.value * size.width, cursorY.value * size.height),
                    rotation = clickRotation.value,
                    pulse = clickPulse.value,
                )
            }
        }

        val badge = gestureEvent?.toGestureVisual()
        if (showBadge && badge != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
                shape = RoundedCornerShape(999.dp),
                color = Color(0xDD0A121E),
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = badge.icon, color = Color(0xFF00E6FF), fontSize = 15.sp)
                    Text(
                        text = badge.label,
                        color = Color(0xFFE0F2FE),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

private suspend fun moveCursor(
    cursorX: Animatable<Float, AnimationVector1D>,
    cursorY: Animatable<Float, AnimationVector1D>,
    target: Offset,
    hasCursor: Boolean,
) {
    if (!hasCursor) {
        cursorX.snapTo(target.x)
        cursorY.snapTo(target.y)
        return
    }
    kotlinx.coroutines.coroutineScope {
        launch { cursorX.animateTo(target.x, tween(200, easing = LinearEasing)) }
        launch { cursorY.animateTo(target.y, tween(200, easing = LinearEasing)) }
    }
}

private fun TaskPointerEvent.toGestureVisual(): GestureVisual = when (this) {
    is TaskPointerEvent.Scroll -> GestureVisual(
        startX = startX,
        startY = startY,
        endX = endX,
        endY = endY,
        durationMs = durationMs,
        direction = direction,
        label = "Scroll ${direction.name.lowercase().replaceFirstChar { it.uppercase() }}",
        icon = when (direction) {
            ScrollDirection.UP -> "⤒"
            ScrollDirection.DOWN -> "⤓"
            ScrollDirection.LEFT -> "⇤"
            ScrollDirection.RIGHT -> "⇥"
        },
        displayWidth = displayWidth,
        displayHeight = displayHeight,
    )

    is TaskPointerEvent.Swipe -> GestureVisual(
        startX = startX,
        startY = startY,
        endX = endX,
        endY = endY,
        durationMs = durationMs,
        direction = null,
        label = "Swipe",
        icon = "↝",
        displayWidth = displayWidth,
        displayHeight = displayHeight,
    )

    is TaskPointerEvent.Click -> error("Clicks do not have a gesture track.")
}

private fun mapDisplayPoint(
    x: Int,
    y: Int,
    displayWidth: Int,
    displayHeight: Int,
): Offset = normalizedPoint(x, y, displayWidth, displayHeight)

private fun DrawScope.drawGestureTrack(gesture: GestureVisual, alpha: Float) {
    if (alpha <= 0f) return
    val start = normalizedPoint(gesture.startX, gesture.startY, gesture.displayWidth, gesture.displayHeight)
    val end = normalizedPoint(gesture.endX, gesture.endY, gesture.displayWidth, gesture.displayHeight)
    val mappedStart = Offset(start.x * size.width, start.y * size.height)
    val mappedEnd = Offset(end.x * size.width, end.y * size.height)
    drawLine(
        color = Color(0xFF00E6FF).copy(alpha = alpha * 0.8f),
        start = mappedStart,
        end = mappedEnd,
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawCircle(Color(0xFF00E6FF).copy(alpha = alpha), radius = 2.5.dp.toPx(), center = mappedStart)
    drawCircle(Color(0xFF2B8CDB).copy(alpha = alpha), radius = 2.dp.toPx(), center = mappedEnd)
}

private fun normalizedPoint(x: Int, y: Int, width: Int, height: Int): Offset = Offset(
    x = x.coerceIn(0, width - 1).toFloat() / width,
    y = y.coerceIn(0, height - 1).toFloat() / height,
)

private fun DrawScope.drawArrow(point: Offset, rotation: Float, pulse: Float) {
    val scale = 16.dp.toPx() / 48f
    val left = point.x - 4f * scale
    val top = point.y - 4f * scale
    val path = Path().apply {
        moveTo(left + 4f * scale, top + 4f * scale)
        lineTo(left + 38f * scale, top + 16f * scale)
        lineTo(left + 24f * scale, top + 24f * scale)
        lineTo(left + 16f * scale, top + 38f * scale)
        close()
    }
    if (pulse > 0f) {
        drawCircle(
            color = Color(0xFF00E6FF).copy(alpha = (1f - pulse) * 0.45f),
            radius = 5.dp.toPx() + 16.dp.toPx() * pulse,
            center = point,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
    rotate(rotation, pivot = point) {
        drawPath(
            path = path,
            color = Color(0xFF00E6FF).copy(alpha = 0.42f),
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(path = path, color = Color(0xFF2B8CDB))
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(width = 1.3.dp.toPx(), join = StrokeJoin.Round),
        )
    }
}

private fun DrawScope.drawMouse(
    center: Offset,
    direction: ScrollDirection?,
    wheelProgress: Float,
) {
    val mouseWidth = 14.dp.toPx()
    val mouseHeight = 20.dp.toPx()
    val left = center.x - mouseWidth / 2f
    val top = center.y - mouseHeight / 2f
    val radius = mouseWidth / 2f
    drawRoundRect(
        color = Color(0xFF2B8CDB),
        topLeft = Offset(left, top),
        size = Size(mouseWidth, mouseHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
    )
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(left, top),
        size = Size(mouseWidth, mouseHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        style = Stroke(width = 1.3.dp.toPx()),
    )
    drawLine(
        color = Color.White,
        start = Offset(left, top + mouseHeight * 0.48f),
        end = Offset(left + mouseWidth, top + mouseHeight * 0.48f),
        strokeWidth = 1.dp.toPx(),
    )
    val travel = (wheelProgress * 2f - 1f) * 2.5.dp.toPx()
    val wheelX = left + mouseWidth / 2f + when (direction) {
        ScrollDirection.LEFT, ScrollDirection.RIGHT -> travel
        else -> 0f
    }
    val wheelY = top + mouseHeight * 0.25f + when (direction) {
        ScrollDirection.UP, ScrollDirection.DOWN -> travel
        else -> 0f
    }
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(wheelX - 1.5.dp.toPx(), wheelY - 2.5.dp.toPx()),
        size = Size(3.dp.toPx(), 5.dp.toPx()),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
    )
}
