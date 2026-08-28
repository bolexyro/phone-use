package com.phonecontrol.assistant.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phonecontrol.assistant.MainActivity
import com.phonecontrol.assistant.PhoneControlApplication
import com.phonecontrol.assistant.R
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
        createNotificationChannel()
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
                intent?.getStringExtra(EXTRA_REQUEST)
                    ?.takeIf(String::isNotBlank)
                    ?.let(coordinator::start)
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
            is SessionState.Running -> state.currentPurpose
            is SessionState.Paused -> "Paused · ${state.currentPurpose}"
            is SessionState.Stopped -> "Stopped"
            is SessionState.Completed -> "Completed"
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun pendingIntentImmutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val ACTION_START = "com.phonecontrol.assistant.action.START"
        const val ACTION_TOGGLE_PAUSE = "com.phonecontrol.assistant.action.TOGGLE_PAUSE"
        const val ACTION_STOP = "com.phonecontrol.assistant.action.STOP"
        const val EXTRA_REQUEST = "com.phonecontrol.assistant.extra.REQUEST"

        private const val CHANNEL_ID = "assistant_sessions"
        private const val NOTIFICATION_ID = 4201
        private const val REQUEST_OPEN_APP = 4202
        private const val REQUEST_TOGGLE_PAUSE = 4203
        private const val REQUEST_STOP = 4204
    }
}
