package com.phonecontrol.assistant.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface as AndroidSurface
import android.view.TextureView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.execution.TaskDisplayGeometry
import com.phonecontrol.assistant.R

/**
 * The state rendered by [LiveDisplayPreview].
 *
 * [aspectRatio] describes the preview card only. It is deliberately not sent
 * to the display controller: the controller owns a fixed virtual-display
 * buffer geometry and must not resize it in response to Compose layout.
 */
data class LiveDisplayPreviewState(
    val status: LiveDisplayPreviewStatus,
    val appLabel: String? = null,
    val message: String? = null,
    val aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
    /** Identifies the task/display session using this surface. */
    val sessionKey: String? = null,
    /** Latest successful gesture to render above the read-only stream. */
    val pointerEvent: TaskPointerEvent? = null,
    /** Sanitized purpose shown in the full-screen viewer footer. */
    val purpose: String? = null,
    /** The latest tool associated with [purpose], used for its activity tint. */
    val currentToolName: String? = null,
) {
    companion object {
        fun unavailable(
            message: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
            currentToolName: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.UNAVAILABLE,
                message = message,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
                currentToolName = currentToolName,
            )

        fun connecting(
            appLabel: String? = null,
            message: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
            currentToolName: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.CONNECTING,
                appLabel = appLabel,
                message = message,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
                currentToolName = currentToolName,
            )

        fun live(
            appLabel: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
            currentToolName: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.LIVE,
                appLabel = appLabel,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
                currentToolName = currentToolName,
            )

        fun error(
            message: String,
            appLabel: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
            currentToolName: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.ERROR,
                appLabel = appLabel,
                message = message,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
                currentToolName = currentToolName,
            )
    }
}

/** Lifecycle values used by the display manager UI. */
enum class TaskDisplayLifecycle {
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    STOPPED,
    UNAVAILABLE,
    ENDED,
    EXPIRED,
}

/**
 * UI-facing display record. The backend is free to maintain a richer
 * persisted record; MainActivity maps that record into this small immutable
 * view model so the UI does not depend on native display implementation
 * details.
 */
data class TaskDisplayUiRecord(
    val sessionKey: String,
    val taskId: String = sessionKey,
    val packageName: String? = null,
    val appLabel: String? = null,
    val displayId: Int? = null,
    val geometry: TaskDisplayGeometry? = null,
    val lifecycle: TaskDisplayLifecycle = TaskDisplayLifecycle.RUNNING,
    val currentPurpose: String? = null,
    val createdAtEpochMs: Long = 0L,
    val terminalAtEpochMs: Long? = null,
    val expiresAtEpochMs: Long? = null,
    val error: String? = null,
    val previewState: LiveDisplayPreviewState? = null,
    /** Latest tool associated with [currentPurpose], when available in memory. */
    val currentToolName: String? = null,
)

enum class LiveDisplayPreviewStatus {
    UNAVAILABLE,
    CONNECTING,
    LIVE,
    ERROR,
}

/** Width / height for the preview card, independent of the display buffer. */
const val DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO = 9f / 16f

private const val FULLSCREEN_DISPLAY_SCALE = 0.90f
private const val FULLSCREEN_DISPLAY_CORNER_RADIUS_DP = 12

/**
 * Renders the agent's virtual display continuously into a read-only surface.
 *
 * The callbacks are the only bridge to the display implementation. A local
 * virtual display can render directly into [AndroidSurface], while a decoder-backed
 * implementation can use the same surface as its output target. The preview
 * consumes all touch events and never forwards input to the rendered app.
 * Surface dimensions are intentionally not exposed as a resize request.
 */
@Composable
fun LiveDisplayPreview(
    state: LiveDisplayPreviewState,
    modifier: Modifier = Modifier,
    onSurfaceAvailable: (AndroidSurface) -> Unit,
    onSurfaceDestroyed: (AndroidSurface) -> Unit,
    onExpand: () -> Unit = {},
) {
    val latestOnSurfaceAvailable = rememberUpdatedState(onSurfaceAvailable)
    val latestOnSurfaceDestroyed = rememberUpdatedState(onSurfaceDestroyed)
    var textureView by remember { mutableStateOf<ReadOnlyPreviewTextureView?>(null) }
    val colors = LocalAssistantColors.current

    DisposableEffect(textureView, state.sessionKey) {
        val view = textureView
        if (view == null) {
            onDispose { }
        } else {
            // TextureView keeps decoder output inside the normal view
            // hierarchy. SurfaceView uses a separate window, which can go
            // blank while this preview is moved by the conversation's
            // LazyColumn during a scroll.
            view.setSurfaceCallbacks(
                onAvailable = { surface -> latestOnSurfaceAvailable.value(surface) },
                onDestroyed = { surface -> latestOnSurfaceDestroyed.value(surface) },
            )
            onDispose {
                view.clearSurfaceCallbacks()
            }
        }
    }

    val previewAspectRatio = state.aspectRatio.takeIf { it.isFinite() && it > 0f }
        ?: DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp, max = 360.dp)
            .background(colors.surfaceCard, shape)
            .clip(shape)
            .semantics { contentDescription = "Live app preview" },
        contentAlignment = Alignment.Center,
    ) {
        val boundedMaxHeight = maxHeight.takeIf { it.value.isFinite() }
            ?: (maxWidth / previewAspectRatio)
        val previewWidth = minOf(maxWidth, boundedMaxHeight * previewAspectRatio)
        val previewHeight = previewWidth / previewAspectRatio

        MaterialSurface(
            modifier = Modifier
                .width(previewWidth)
                .height(previewHeight),
            shape = shape,
            color = colors.surfaceCard,
            shadowElevation = 2.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        ReadOnlyPreviewTextureView(context).also { view ->
                            textureView = view
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        // The preview is not an input surface for the agent. It
                        // consumes taps so a preview cannot accidentally become a
                        // second control path.
                        .background(colors.surfaceCard),
                    update = { view ->
                        view.contentDescription = "Live app preview"
                    },
                )

                if (state.status != LiveDisplayPreviewStatus.LIVE) {
                    PreviewStatusOverlay(state = state)
                }

                if (state.status == LiveDisplayPreviewStatus.LIVE) {
                    TaskPointerOverlay(event = state.pointerEvent)
                }
            }
        }

        // Keep the expand affordance in the letterbox outside the phone surface
        // so it never obscures the app or looks like an app control.
        MaterialSurface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onExpand),
            shape = RoundedCornerShape(999.dp),
            color = colors.composerBackground.copy(alpha = 0.92f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_fullscreen),
                    contentDescription = "Open full-screen viewer",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

/**
 * Full-screen read-only viewer for one task display.
 *
 * Only this composable owns the expanded TextureView. Callers should remove
 * the inline preview while [visible] so the same decoder surface is not
 * attached twice. The surface remains deliberately non-clickable.
 */
@Composable
fun FullScreenLiveDisplayViewer(
    record: TaskDisplayUiRecord,
    state: LiveDisplayPreviewState,
    onDismiss: () -> Unit,
    onSurfaceAvailable: (AndroidSurface) -> Unit,
    onSurfaceDestroyed: (AndroidSurface) -> Unit,
    onRetry: () -> Unit = {},
    onAcknowledgeAttention: () -> Boolean = { false },
    onStopSession: () -> Unit = {},
) {
    val colors = LocalAssistantColors.current
    val title = record.appLabel
        ?: state.appLabel
        ?: "Task display"
    val status = record.lifecycle.displayLabel()
    val purpose = record.currentPurpose
        ?: state.purpose
        ?: state.status.displayLabel()
    val isCompleted = record.lifecycle == TaskDisplayLifecycle.COMPLETED
    val showAttentionActions = record.lifecycle == TaskDisplayLifecycle.PAUSED &&
        (record.currentPurpose.isAttentionPurpose() || state.purpose.isAttentionPurpose())
    val purposeIconColor = when {
        showAttentionActions -> colors.warningAmber
        record.lifecycle == TaskDisplayLifecycle.FAILED -> colors.errorRed
        else -> toolActivityColor(
            toolName = state.currentToolName ?: record.currentToolName,
            colors = colors,
            fallback = colors.accentBlue,
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        MaterialSurface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MaterialSurface(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onDismiss),
                        shape = RoundedCornerShape(999.dp),
                        color = colors.composerBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderColor),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Back",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 14.dp),
                    ) {
                        Text(
                            text = title,
                            color = colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            text = status,
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    val aspectRatio = state.aspectRatio.takeIf { it.isFinite() && it > 0f }
                        ?: DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO
                    val maxHeight = maxHeight.takeIf { it.value.isFinite() }
                        ?: (maxWidth / aspectRatio)
                    val surfaceWidth = minOf(maxWidth, maxHeight * aspectRatio) * FULLSCREEN_DISPLAY_SCALE
                    val surfaceHeight = surfaceWidth / aspectRatio
                    FullScreenPreviewSurface(
                        state = state,
                        width = surfaceWidth,
                        height = surfaceHeight,
                        onSurfaceAvailable = onSurfaceAvailable,
                        onSurfaceDestroyed = onSurfaceDestroyed,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.background)
                        .navigationBarsPadding(),
                ) {
                    Column(
                        modifier = Modifier.padding(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 20.dp),
                    ) {
                        if (!isCompleted) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_connected_nodes),
                                    contentDescription = "MCP activity",
                                    tint = purposeIconColor,
                                    modifier = Modifier.size(18.dp),
                                )
                                AnimatedPurposeText(
                                    text = purpose.withTrailingEllipsis(),
                                    maxLines = 2,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                        if (showAttentionActions) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(
                                    12.dp,
                                    Alignment.CenterHorizontally,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = {
                                        onAcknowledgeAttention()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.warningAmber,
                                        contentColor = Color.White,
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Done", fontSize = 13.sp)
                                }
                                OutlinedButton(
                                    onClick = onStopSession,
                                    border = BorderStroke(1.dp, colors.borderColor),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Stop", color = colors.textSecondary, fontSize = 13.sp)
                                }
                            }
                        }
                        if (state.status == LiveDisplayPreviewStatus.ERROR) {
                            Button(
                                onClick = onRetry,
                                modifier = Modifier.padding(top = 10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accentBlue,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text("Retry preview")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Renders the safe, sanitized purpose with the same moving shimmer used by the task status. */
@Composable
private fun AnimatedPurposeText(
    text: String,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    val transition = rememberInfiniteTransition(label = "task_display_purpose_shimmer")
    val shimmerTranslate by transition.animateFloat(
        initialValue = -180f,
        targetValue = 480f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "task_display_purpose_translate",
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            colors.accentBlue.copy(alpha = 0.35f),
            colors.accentBlue,
            colors.textPrimary,
            colors.accentBlue,
            colors.accentBlue.copy(alpha = 0.35f),
        ),
        start = Offset(shimmerTranslate, 0f),
        end = Offset(shimmerTranslate + 180f, 0f),
    )
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            brush = shimmerBrush,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = maxLines,
    )
}

private fun String.withTrailingEllipsis(): String {
    val trimmed = trimEnd()
    return when {
        trimmed.endsWith("...") || trimmed.endsWith("…") -> trimmed
        else -> "$trimmed..."
    }
}

private fun String?.isAttentionPurpose(): Boolean =
    this?.equals("Needs your attention", ignoreCase = true) == true

@Composable
private fun FullScreenPreviewSurface(
    state: LiveDisplayPreviewState,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    onSurfaceAvailable: (AndroidSurface) -> Unit,
    onSurfaceDestroyed: (AndroidSurface) -> Unit,
) {
    val latestOnSurfaceAvailable = rememberUpdatedState(onSurfaceAvailable)
    val latestOnSurfaceDestroyed = rememberUpdatedState(onSurfaceDestroyed)
    var textureView by remember { mutableStateOf<ReadOnlyPreviewTextureView?>(null) }
    val phoneShape = RoundedCornerShape(FULLSCREEN_DISPLAY_CORNER_RADIUS_DP.dp)
    val colors = LocalAssistantColors.current

    DisposableEffect(textureView, state.sessionKey) {
        val view = textureView
        if (view == null) {
            onDispose { }
        } else {
            view.setSurfaceCallbacks(
                onAvailable = { surface -> latestOnSurfaceAvailable.value(surface) },
                onDestroyed = { surface -> latestOnSurfaceDestroyed.value(surface) },
            )
            onDispose { view.clearSurfaceCallbacks() }
        }
    }

    MaterialSurface(
        modifier = Modifier
            .width(width)
            .height(height)
            // Keep the rendered display geometry unchanged while giving the
            // viewer a clear, soft blue frame around the phone-shaped surface.
            .shadow(
                elevation = 20.dp,
                shape = phoneShape,
                clip = false,
                ambientColor = colors.accentBlue.copy(alpha = 0.72f),
                spotColor = colors.accentBlue.copy(alpha = 0.58f),
            )
            .border(BorderStroke(2.dp, colors.accentBlue.copy(alpha = 0.86f)), phoneShape)
            .clip(phoneShape),
        shape = phoneShape,
        color = Color.Black,
        shadowElevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    ReadOnlyPreviewTextureView(context).also { textureView = it }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view -> view.contentDescription = "Full-screen live app preview" },
            )
            if (state.status == LiveDisplayPreviewStatus.LIVE) {
                TaskPointerOverlay(event = state.pointerEvent)
            }
            if (state.status != LiveDisplayPreviewStatus.LIVE) {
                PreviewStatusOverlay(state)
            }
        }
    }
}

internal fun TaskDisplayLifecycle.displayLabel(): String = when (this) {
    TaskDisplayLifecycle.RUNNING -> "Running"
    TaskDisplayLifecycle.PAUSED -> "Waiting"
    TaskDisplayLifecycle.COMPLETED -> "Completed"
    TaskDisplayLifecycle.FAILED -> "Failed"
    TaskDisplayLifecycle.STOPPED -> "Stopped"
    TaskDisplayLifecycle.UNAVAILABLE -> "Unavailable"
    TaskDisplayLifecycle.ENDED -> "Ended"
    TaskDisplayLifecycle.EXPIRED -> "Expired"
}

internal fun LiveDisplayPreviewStatus.displayLabel(): String = when (this) {
    LiveDisplayPreviewStatus.UNAVAILABLE -> "Preview unavailable"
    LiveDisplayPreviewStatus.CONNECTING -> "Connecting to preview"
    LiveDisplayPreviewStatus.LIVE -> "Streaming app"
    LiveDisplayPreviewStatus.ERROR -> "Preview error"
}

@Composable
private fun PreviewStatusOverlay(state: LiveDisplayPreviewState) {
    val title: String
    val detail: String
    when (state.status) {
        LiveDisplayPreviewStatus.UNAVAILABLE -> {
            title = "Live preview unavailable"
            detail = state.message ?: "The agent has no active app display."
        }
        LiveDisplayPreviewStatus.CONNECTING -> {
            title = "Connecting to live preview"
            detail = state.message ?: "The app will appear here when the display is ready."
        }
        LiveDisplayPreviewStatus.ERROR -> {
            title = "Live preview unavailable"
            detail = state.message ?: "The app display could not be attached."
        }
        LiveDisplayPreviewStatus.LIVE -> return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                modifier = Modifier.padding(top = 6.dp),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
            )
        }
    }
}

/** A scroll-safe decoder target with no control semantics. */
private class ReadOnlyPreviewTextureView(context: Context) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private var decoderSurface: AndroidSurface? = null
    private var onAvailable: ((AndroidSurface) -> Unit)? = null
    private var onDestroyed: ((AndroidSurface) -> Unit)? = null

    init {
        surfaceTextureListener = this
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        setOnTouchListener { _, _ -> true }
    }

    fun setSurfaceCallbacks(
        onAvailable: (AndroidSurface) -> Unit,
        onDestroyed: (AndroidSurface) -> Unit,
    ) {
        this.onAvailable = onAvailable
        this.onDestroyed = onDestroyed
        decoderSurface?.takeIf(AndroidSurface::isValid)?.let(onAvailable)
    }

    fun clearSurfaceCallbacks() {
        decoderSurface?.let { surface -> onDestroyed?.invoke(surface) }
        onAvailable = null
        onDestroyed = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        decoderSurface?.let { previous ->
            onDestroyed?.invoke(previous)
            previous.release()
        }
        AndroidSurface(surface).also { created ->
            decoderSurface = created
            onAvailable?.invoke(created)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        decoderSurface?.let { current ->
            onDestroyed?.invoke(current)
            current.release()
        }
        decoderSurface = null
        return true
    }
}
