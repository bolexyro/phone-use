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
import com.phonecontrol.assistant.session.AssistantForegroundService
import com.phonecontrol.assistant.ui.PhoneControlApp

class MainActivity : ComponentActivity() {
    private var pendingRequest: String? = null
    private var pendingConversationId: String? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingRequest?.let { launchSession(it, pendingConversationId) }
        }
        pendingRequest = null
        pendingConversationId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        setContent {
            PhoneControlApp(
                initialConversationId = initialConversationId,
                onRunRequest = ::startSession,
                onStopSession = ::stopSession,
                onSteerRequest = ::steerSession,
            )
        }
    }

    private fun startSession(request: String, conversationId: String?) {
        if (request.isBlank()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingRequest = request
            pendingConversationId = conversationId
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchSession(request, conversationId)
        }
    }

    private fun launchSession(request: String, conversationId: String? = null) {
        val intent = Intent(this, AssistantForegroundService::class.java)
            .setAction(AssistantForegroundService.ACTION_START)
            .putExtra(AssistantForegroundService.EXTRA_REQUEST, request)
        if (!conversationId.isNullOrBlank()) {
            intent.putExtra(AssistantForegroundService.EXTRA_CONVERSATION_ID, conversationId)
        }
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
