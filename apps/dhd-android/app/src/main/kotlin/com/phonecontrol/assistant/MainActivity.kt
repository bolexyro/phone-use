package com.phonecontrol.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.phonecontrol.assistant.developer.TaskPreviewState
import com.phonecontrol.assistant.session.AssistantForegroundService
import com.phonecontrol.assistant.ui.PhoneControlApp
import com.phonecontrol.assistant.ui.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.LiveDisplayPreviewStatus

class MainActivity : ComponentActivity() {
    private var pendingRequest: String? = null
    private var pendingConversationId: String? = null
    private var pendingReasoningEffort: String? = null
    private var pendingFastMode: Boolean = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingRequest?.let {
                launchSession(it, pendingConversationId, pendingReasoningEffort, pendingFastMode)
            }
        }
        pendingRequest = null
        pendingConversationId = null
        pendingReasoningEffort = null
        pendingFastMode = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        val app = application as PhoneControlApplication
        setContent {
            val display by app.taskDisplayBackend.activeSession.collectAsState()
            val playback by app.taskDisplayBackend.previewState.collectAsState()
            val pointerEvent by app.sessionCoordinator.pointerEvent.collectAsState()
            val preview = display?.let { session ->
                val error = playback as? TaskPreviewState.Error
                LiveDisplayPreviewState(
                    status = when {
                        error?.sessionKey == session.sessionKey -> LiveDisplayPreviewStatus.ERROR
                        (playback as? TaskPreviewState.Attached)?.session == session ->
                            LiveDisplayPreviewStatus.LIVE
                        else -> LiveDisplayPreviewStatus.CONNECTING
                    },
                    message = error?.takeIf { it.sessionKey == session.sessionKey }?.message,
                    aspectRatio = session.geometry.width.toFloat() / session.geometry.height,
                    sessionKey = session.sessionKey,
                    pointerEvent = pointerEvent?.takeIf { it.sessionId == session.sessionKey },
                )
            }
            PhoneControlApp(
                initialConversationId = initialConversationId,
                onRunRequest = ::startSession,
                onStopSession = ::stopSession,
                onAcknowledgeAttention = { app.sessionCoordinator.acknowledgeAttention() },
                onSteerRequest = ::steerSession,
                previewState = preview,
                onPreviewSurfaceAvailable = { surface ->
                    display?.let { app.attachTaskPreview(it, surface) }
                },
                onPreviewSurfaceDestroyed = { surface ->
                    display?.let { app.detachTaskPreview(it, surface) }
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        (application as? PhoneControlApplication)?.let { app ->
            app.developerModeController.refresh()
            app.devBridgeServer.requestCodexWarmup()
        }
    }

    private fun startSession(
        request: String,
        conversationId: String?,
        reasoningEffort: String?,
        fastMode: Boolean,
    ) {
        if (request.isBlank()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingRequest = request
            pendingConversationId = conversationId
            pendingReasoningEffort = reasoningEffort
            pendingFastMode = fastMode
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchSession(request, conversationId, reasoningEffort, fastMode)
        }
    }

    private fun launchSession(
        request: String,
        conversationId: String? = null,
        reasoningEffort: String? = null,
        fastMode: Boolean = false,
    ) {
        val intent = Intent(this, AssistantForegroundService::class.java)
            .setAction(AssistantForegroundService.ACTION_START)
            .putExtra(AssistantForegroundService.EXTRA_REQUEST, request)
        if (!conversationId.isNullOrBlank()) {
            intent.putExtra(AssistantForegroundService.EXTRA_CONVERSATION_ID, conversationId)
        }
        if (!reasoningEffort.isNullOrBlank()) {
            intent.putExtra(AssistantForegroundService.EXTRA_REASONING_EFFORT, reasoningEffort)
        }
        intent.putExtra(AssistantForegroundService.EXTRA_FAST_MODE, fastMode)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSession() {
        startService(
            Intent(this, AssistantForegroundService::class.java)
                .setAction(AssistantForegroundService.ACTION_STOP),
        )
    }

    private fun steerSession(text: String): Boolean =
        (application as PhoneControlApplication).sessionCoordinator.enqueueSteer(text) != null

    companion object {
        const val EXTRA_CONVERSATION_ID = "com.phonecontrol.assistant.extra.CONVERSATION_ID"
    }
}
