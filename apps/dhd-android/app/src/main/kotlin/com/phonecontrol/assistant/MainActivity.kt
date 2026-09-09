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
import androidx.compose.runtime.produceState
import com.phonecontrol.assistant.developer.TaskPreviewState
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.taskDisplayReference
import com.phonecontrol.assistant.session.AssistantForegroundService
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.PhoneControlApp
import com.phonecontrol.assistant.ui.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.LiveDisplayPreviewStatus
import com.phonecontrol.assistant.ui.TaskDisplayLifecycle
import com.phonecontrol.assistant.ui.TaskDisplayUiRecord

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
        val appPackageManager = packageManager
        setContent {
            val display by app.taskDisplayBackend.activeSession.collectAsState()
            val playback by app.taskDisplayBackend.previewState.collectAsState()
            val previewStates by app.taskDisplayBackend.previewStates.collectAsState()
            val backendDisplayRecords by app.taskDisplayBackend.displayRecords.collectAsState()
            val sessionState by app.sessionCoordinator.state.collectAsState()
            val events by app.sessionCoordinator.events.collectAsState()
            val pointerEvent by app.sessionCoordinator.pointerEvent.collectAsState()
            val purpose = when (val state = sessionState) {
                is com.phonecontrol.assistant.session.SessionState.Running -> state.currentPurpose
                is com.phonecontrol.assistant.session.SessionState.Paused -> state.currentPurpose
                is com.phonecontrol.assistant.session.SessionState.Stopped -> state.reason
                is com.phonecontrol.assistant.session.SessionState.Completed -> "Task complete"
                com.phonecontrol.assistant.session.SessionState.Idle -> null
            }
            val coordinatorSessionKey = sessionState.sessionKeyOrNull()
            // A new coordinator run can claim a retained display whose native
            // owner key belongs to the previous run. Resolve that binding for
            // the inline viewer so the UI follows the selected display rather
            // than assuming the two keys are identical.
            val resolvedDisplayForRun by produceState<TaskDisplaySession?>(
                initialValue = null,
                key1 = coordinatorSessionKey,
                key2 = display?.sessionKey,
                // Selecting a retained display with displayRef publishes its
                // updated registry record after the initial lookup. Include
                // the registry in the keys so the suspended lookup retries
                // once that binding becomes visible to the UI.
                key3 = backendDisplayRecords,
            ) {
                value = coordinatorSessionKey?.let { app.taskDisplayBackend.current(it) }
            }
            val displayForRun = resolvedDisplayForRun
                ?: display?.takeIf { it.sessionKey == coordinatorSessionKey }
            val activeDisplayOwnerKey = displayForRun?.sessionKey
            val currentToolName = coordinatorSessionKey?.let { sessionKey ->
                events.asReversed()
                    .firstOrNull { event ->
                        event.sessionId == sessionKey && !event.toolName.isNullOrBlank()
                    }
                    ?.toolName
            }
            val preview = displayForRun?.let { session ->
                // The global preview state is retained for the legacy single
                // viewer. Once a displayRef selects a retained display, use
                // its per-session state so another display's decoder cannot
                // make this preview appear stuck in Connecting.
                val playbackForSession = previewStates[session.sessionKey]
                    ?: playback.forSession(session.sessionKey)
                val error = playbackForSession as? TaskPreviewState.Error
                val appLabel = session.packageName.applicationLabel(appPackageManager)
                LiveDisplayPreviewState(
                    status = when {
                        error?.sessionKey == session.sessionKey -> LiveDisplayPreviewStatus.ERROR
                        (playbackForSession as? TaskPreviewState.Attached)?.session == session ->
                            LiveDisplayPreviewStatus.LIVE
                        else -> LiveDisplayPreviewStatus.CONNECTING
                    },
                    message = error?.takeIf { it.sessionKey == session.sessionKey }?.message,
                    aspectRatio = session.geometry.width.toFloat() / session.geometry.height,
                    appLabel = appLabel,
                    sessionKey = session.sessionKey,
                    runSessionKey = coordinatorSessionKey,
                    pointerEvent = pointerEvent?.takeIf {
                        it.sessionId == coordinatorSessionKey || it.sessionId == session.sessionKey
                    },
                    purpose = purpose,
                    currentToolName = currentToolName,
                )
            }
            val mappedRecords = backendDisplayRecords.map { record ->
                record.toUiRecord(
                    preview = previewStates[record.sessionKey],
                    packageManager = appPackageManager,
                    currentToolName = currentToolName.takeIf { record.sessionKey == activeDisplayOwnerKey },
                )
            }.toMutableList()
            // A newly created session may be visible through activeSession a
            // frame before its durable registry record is published. Keep the
            // manager populated during that small handoff window.
            displayForRun?.let { session ->
                val activeRecord = TaskDisplayUiRecord(
                    sessionKey = session.sessionKey,
                    taskId = session.taskId,
                    packageName = session.packageName,
                    appLabel = session.packageName.applicationLabel(appPackageManager),
                    displayId = session.displayId,
                    displayRef = taskDisplayReference(session.sessionKey, session.displayId),
                    geometry = session.geometry,
                    lifecycle = sessionState.toUiDisplayLifecycle(),
                    currentPurpose = purpose,
                    createdAtEpochMs = sessionState.startedAtEpochMsOrZero(),
                    currentToolName = currentToolName,
                    previewState = preview,
                )
                val index = mappedRecords.indexOfFirst { it.sessionKey == session.sessionKey }
                if (index >= 0) {
                    val persisted = mappedRecords[index]
                    // The coordinator is authoritative while this run is
                    // active. Once it reaches a terminal state, retain the
                    // backend's precise completed/failed/stopped status and
                    // purpose instead of replacing it with a generic state
                    // from the UI process.
                    val runIsActive = sessionState is com.phonecontrol.assistant.session.SessionState.Running ||
                        sessionState is com.phonecontrol.assistant.session.SessionState.Paused
                    mappedRecords[index] = if (runIsActive) {
                        persisted.copy(
                            lifecycle = activeRecord.lifecycle,
                            currentPurpose = purpose ?: persisted.currentPurpose,
                            currentToolName = currentToolName ?: persisted.currentToolName,
                            previewState = preview ?: persisted.previewState,
                        )
                    } else {
                        persisted.copy(previewState = preview ?: persisted.previewState)
                    }
                } else {
                    mappedRecords += activeRecord
                }
            }
            PhoneControlApp(
                initialConversationId = initialConversationId,
                onRunRequest = ::startSession,
                onStopSession = ::stopSession,
                onAcknowledgeAttention = { app.sessionCoordinator.acknowledgeAttention() },
                onSteerRequest = ::steerSession,
                previewState = preview,
                displayRecords = mappedRecords,
                onPreviewSurfaceAvailable = { surface ->
                    displayForRun?.let { app.attachTaskPreview(it, surface) }
                },
                onPreviewSurfaceDestroyed = { surface ->
                    displayForRun?.let { app.detachTaskPreview(it, surface) }
                },
                onTaskDisplaySurfaceAvailable = { record, surface ->
                    app.attachTaskPreview(record.sessionKey, surface)
                },
                onTaskDisplaySurfaceDestroyed = { record, surface ->
                    app.detachTaskPreview(record.sessionKey, surface)
                },
                onEndTaskDisplay = { record ->
                    app.endTaskDisplay(record.displayId, record.displayRef)
                },
                onRetryTaskDisplayPreview = { record ->
                    app.retryTaskPreview(record.sessionKey)
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

private fun TaskDisplayRecord.toUiRecord(
    preview: TaskPreviewState?,
    packageManager: PackageManager,
    currentToolName: String? = null,
): TaskDisplayUiRecord = TaskDisplayUiRecord(
    sessionKey = sessionKey,
    taskId = taskId,
    packageName = packageName,
    appLabel = packageName.applicationLabel(packageManager),
    displayId = displayId,
    displayRef = this.displayRef,
    geometry = geometry,
    lifecycle = status.toUiLifecycle(),
    currentPurpose = lastPurpose,
    createdAtEpochMs = createdAtEpochMs,
    terminalAtEpochMs = terminalAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    error = error,
    currentToolName = currentToolName,
    previewState = preview.toUiPreview(
        geometry = geometry,
        sessionKey = sessionKey,
        appLabel = packageName.applicationLabel(packageManager),
        purpose = lastPurpose,
        currentToolName = currentToolName,
    ),
)

private fun TaskPreviewState?.toUiPreview(
    geometry: com.phonecontrol.assistant.execution.TaskDisplayGeometry,
    sessionKey: String,
    appLabel: String?,
    purpose: String,
    currentToolName: String? = null,
): LiveDisplayPreviewState? {
    val ratio = geometry.width.toFloat() / geometry.height.toFloat()
    return when (this) {
        is TaskPreviewState.Connecting -> LiveDisplayPreviewState(
            status = LiveDisplayPreviewStatus.CONNECTING,
            appLabel = appLabel,
            aspectRatio = ratio,
            sessionKey = sessionKey,
            purpose = purpose,
            currentToolName = currentToolName,
        )
        is TaskPreviewState.Attached -> LiveDisplayPreviewState(
            status = LiveDisplayPreviewStatus.LIVE,
            appLabel = appLabel,
            aspectRatio = ratio,
            sessionKey = sessionKey,
            purpose = purpose,
            currentToolName = currentToolName,
        )
        is TaskPreviewState.Error -> LiveDisplayPreviewState.error(
            message = message,
            appLabel = appLabel,
            aspectRatio = ratio,
            sessionKey = sessionKey,
        ).copy(purpose = purpose, currentToolName = currentToolName)
        else -> null
    }
}

private fun String.applicationLabel(packageManager: PackageManager): String? {
    if (isBlank()) return null
    return runCatching {
        val info = packageManager.getApplicationInfo(this, 0)
        packageManager.getApplicationLabel(info).toString().takeIf(String::isNotBlank)
    }.getOrNull()
}

private fun TaskDisplayStatus.toUiLifecycle(): TaskDisplayLifecycle = when (this) {
    TaskDisplayStatus.RUNNING -> TaskDisplayLifecycle.RUNNING
    TaskDisplayStatus.PAUSED -> TaskDisplayLifecycle.PAUSED
    TaskDisplayStatus.COMPLETED -> TaskDisplayLifecycle.COMPLETED
    TaskDisplayStatus.FAILED -> TaskDisplayLifecycle.FAILED
    TaskDisplayStatus.STOPPED -> TaskDisplayLifecycle.STOPPED
    TaskDisplayStatus.UNAVAILABLE -> TaskDisplayLifecycle.UNAVAILABLE
    TaskDisplayStatus.ENDED -> TaskDisplayLifecycle.ENDED
    TaskDisplayStatus.EXPIRED -> TaskDisplayLifecycle.EXPIRED
}

private fun SessionState.toUiDisplayLifecycle(): TaskDisplayLifecycle = when (this) {
    is SessionState.Running -> if (attentionReason != null) {
        TaskDisplayLifecycle.PAUSED
    } else {
        TaskDisplayLifecycle.RUNNING
    }
    is SessionState.Paused -> TaskDisplayLifecycle.PAUSED
    is SessionState.Stopped -> TaskDisplayLifecycle.STOPPED
    is SessionState.Completed -> TaskDisplayLifecycle.COMPLETED
    SessionState.Idle -> TaskDisplayLifecycle.UNAVAILABLE
}

private fun SessionState.startedAtEpochMsOrZero(): Long = when (this) {
    is SessionState.Running -> startedAtEpochMs
    is SessionState.Paused -> startedAtEpochMs
    else -> 0L
}

private fun TaskPreviewState.forSession(sessionKey: String): TaskPreviewState? = when (this) {
    TaskPreviewState.Detached -> null
    is TaskPreviewState.Connecting -> takeIf { session.sessionKey == sessionKey }
    is TaskPreviewState.Attached -> takeIf { session.sessionKey == sessionKey }
    is TaskPreviewState.Error -> takeIf { this.sessionKey == sessionKey }
}

private fun SessionState.sessionKeyOrNull(): String? = when (this) {
    SessionState.Idle -> null
    is SessionState.Running -> sessionId
    is SessionState.Paused -> sessionId
    is SessionState.Stopped -> sessionId
    is SessionState.Completed -> sessionId
}
