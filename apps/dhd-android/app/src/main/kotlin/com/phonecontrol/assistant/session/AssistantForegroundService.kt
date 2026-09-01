package com.phonecontrol.assistant.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phonecontrol.assistant.MainActivity
import com.phonecontrol.assistant.PhoneControlApplication
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.domain.ReasoningEffort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AssistantForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator: SessionCoordinator
        get() = (application as PhoneControlApplication).sessionCoordinator

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForegroundCompat(buildNotification(coordinator.state.value))
        serviceScope.launch {
            coordinator.state.collectLatest { state ->
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> coordinator.togglePause()
            ACTION_STOP -> {
                coordinator.stop("Stopped from the notification.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }

            ACTION_START, null -> {
                val request = intent?.getStringExtra(EXTRA_REQUEST)
                    ?.takeIf(String::isNotBlank)
                if (request != null) {
                    coordinator.start(
                        request = request,
                        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID),
                        reasoningEffort = intent.getStringExtra(EXTRA_REASONING_EFFORT)
                            ?: ReasoningEffort.default.codexValue,
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(state: SessionState) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: SessionState): Notification {
        val isPaused = state is SessionState.Paused
        val status = when (state) {
            SessionState.Idle -> "Ready"
            is SessionState.Running -> notificationPurpose(state.currentPurpose)
            is SessionState.Paused -> "Paused · ${notificationPurpose(state.currentPurpose)}"
            is SessionState.Stopped -> "Stopped"
            is SessionState.Completed -> "Completed"
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, state.conversationIdOrNull())
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
        val pauseIntent = PendingIntent.getService(
            this,
            REQUEST_TOGGLE_PAUSE,
            Intent(this, AssistantForegroundService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AssistantForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setContentIntent(contentIntent)
            .setOngoing(state is SessionState.Running || state is SessionState.Paused)
            .setOnlyAlertOnce(true)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pauseIntent,
            )
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }

    private fun notificationPurpose(purpose: String): String = when {
        purpose.equals("Preparing request", ignoreCase = true) -> "Connecting to Codex…"
        purpose.equals("Codex is planning", ignoreCase = true) -> "Thinking…"
        purpose.equals("Waiting for desktop Codex bridge", ignoreCase = true) -> "Companion not connected"
        purpose.equals("Needs your attention", ignoreCase = true) -> "DHD needs your attention"
        else -> purpose
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannels() {
        createNotificationChannels(this)
    }

    private fun pendingIntentImmutableFlag(): Int =
        pendingIntentFlags()

    companion object {
        const val ACTION_START = "com.phonecontrol.assistant.action.START"
        const val ACTION_TOGGLE_PAUSE = "com.phonecontrol.assistant.action.TOGGLE_PAUSE"
        const val ACTION_STOP = "com.phonecontrol.assistant.action.STOP"
        const val EXTRA_REQUEST = "com.phonecontrol.assistant.extra.REQUEST"
        const val EXTRA_CONVERSATION_ID = "com.phonecontrol.assistant.extra.CONVERSATION_ID"
        const val EXTRA_REASONING_EFFORT = "com.phonecontrol.assistant.extra.REASONING_EFFORT"

        private const val CHANNEL_ID = "assistant_sessions"
        private const val RESULT_CHANNEL_ID = "assistant_results"
        private const val ATTENTION_CHANNEL_ID = "assistant_attention"
        private const val NOTIFICATION_ID = 4201
        private const val REQUEST_OPEN_APP = 4202
        private const val REQUEST_TOGGLE_PAUSE = 4203
        private const val REQUEST_STOP = 4204
        private const val COMPLETION_NOTIFICATION_ID = 4205
        private const val ATTENTION_NOTIFICATION_ID = 4206
        private const val MAX_NOTIFICATION_TEXT_CHARS = 240

        /** Post a result notification without bringing the assistant to the foreground. */
        fun showCompletionNotification(context: Context, message: String, conversationId: String? = null) {
            createNotificationChannels(context)
            val preview = completionNotificationPreview(message)
            val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.notification_completion_title))
                .setContentText(preview)
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setContentIntent(openAssistantIntent(context, REQUEST_OPEN_APP + 1, conversationId))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(COMPLETION_NOTIFICATION_ID, notification)
        }

        /** Notify the user without launching an Activity or interrupting Watch mode. */
        fun showAttentionNotification(context: Context, reason: String, conversationId: String? = null) {
            createNotificationChannels(context)
            val safeReason = reason.trim()
                .ifBlank { "The phone assistant needs your attention." }
                .take(MAX_NOTIFICATION_TEXT_CHARS)
            val notification = NotificationCompat.Builder(context, ATTENTION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("DHD needs your attention")
                .setContentText(safeReason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(safeReason))
                .setContentIntent(openAssistantIntent(context, REQUEST_OPEN_APP + 2, conversationId))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(ATTENTION_NOTIFICATION_ID, notification)
        }

        private fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannels(
                listOf(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.notification_channel_description)
                    },
                    NotificationChannel(
                        RESULT_CHANNEL_ID,
                        context.getString(R.string.notification_result_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.notification_result_channel_description)
                    },
                    NotificationChannel(
                        ATTENTION_CHANNEL_ID,
                        context.getString(R.string.notification_attention_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = context.getString(R.string.notification_attention_channel_description)
                    },
                ),
            )
        }

        private fun openAssistantIntent(context: Context, requestCode: Int, conversationId: String? = null): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java).apply {
                    if (!conversationId.isNullOrBlank()) {
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                    }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentFlags(),
            )

        private fun pendingIntentFlags(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}

private fun SessionState.conversationIdOrNull(): String? = when (this) {
    SessionState.Idle -> null
    is SessionState.Running -> conversationId
    is SessionState.Paused -> conversationId
    is SessionState.Stopped -> conversationId
    is SessionState.Completed -> conversationId
}
