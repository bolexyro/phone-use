package com.phonecontrol.assistant.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface as AndroidSurface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

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
) {
    companion object {
        fun unavailable(
            message: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.UNAVAILABLE,
                message = message,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
            )

        fun connecting(
            appLabel: String? = null,
            message: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.CONNECTING,
                appLabel = appLabel,
                message = message,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
            )

        fun live(
            appLabel: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.LIVE,
                appLabel = appLabel,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
            )

        fun error(
            message: String,
            appLabel: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.ERROR,
                appLabel = appLabel,
                message = message,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
            )
    }
}

enum class LiveDisplayPreviewStatus {
    UNAVAILABLE,
    CONNECTING,
    LIVE,
    ERROR,
}

/** Width / height for the preview card, independent of the display buffer. */
const val DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO = 9f / 16f

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
) {
    val colors = LocalAssistantColors.current
    val latestOnSurfaceAvailable = rememberUpdatedState(onSurfaceAvailable)
    val latestOnSurfaceDestroyed = rememberUpdatedState(onSurfaceDestroyed)
    var textureView by remember { mutableStateOf<ReadOnlyPreviewTextureView?>(null) }

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
            .background(Color.Black, shape)
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
            color = Color.Black,
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
                        .background(Color.Black),
                    update = { view ->
                        view.contentDescription = "Live app preview"
                    },
                )

                if (state.status != LiveDisplayPreviewStatus.LIVE) {
                    PreviewStatusOverlay(state = state)
                } else {
                    PreviewLiveBadge(
                        appLabel = state.appLabel,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewStatusOverlay(state: LiveDisplayPreviewState) {
    val colors = LocalAssistantColors.current
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
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                modifier = Modifier.padding(top = 6.dp),
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun PreviewLiveBadge(
    appLabel: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    MaterialSurface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.62f),
    ) {
        Text(
            text = appLabel?.let { "LIVE · $it" } ?: "LIVE",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
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
