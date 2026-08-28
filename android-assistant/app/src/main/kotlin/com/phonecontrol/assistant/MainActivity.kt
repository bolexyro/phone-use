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
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pendingRequest?.let(::launchSession)
        pendingRequest = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneControlApp(
                onRunRequest = ::startSession,
                onStopSession = ::stopSession,
            )
        }
    }

    private fun startSession(request: String) {
        if (request.isBlank()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingRequest = request
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchSession(request)
        }
    }

    private fun launchSession(request: String) {
        val intent = Intent(this, AssistantForegroundService::class.java)
            .setAction(AssistantForegroundService.ACTION_START)
            .putExtra(AssistantForegroundService.EXTRA_REQUEST, request)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSession() {
        startService(
            Intent(this, AssistantForegroundService::class.java)
                .setAction(AssistantForegroundService.ACTION_STOP),
        )
    }
}
