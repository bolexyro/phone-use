package com.phonecontrol.assistant.developer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.phonecontrol.assistant.MainActivity
import com.phonecontrol.assistant.PhoneControlApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps the one-time Wireless Debugging pairing flow alive while Settings is foreground. */
class DhdAdbPairingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null
    private var pairingActive = false
    private var foregroundNotificationId = DhdAdbPairingNotification.SEARCHING_NOTIFICATION_ID

    private val controller: DhdAdbController
        get() = (application as PhoneControlApplication).developerModeController

    override fun onCreate() {
        super.onCreate()
        DhdAdbPairingNotification.createChannel(this)
        // A foreground service must have a notification immediately. The
        // first notification deliberately has no input action: it tells the
        // user that DHD is listening for Android's pairing service.
        startForegroundCompat(
            DhdAdbPairingNotification.searchingNotification(this),
            DhdAdbPairingNotification.SEARCHING_NOTIFICATION_ID,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                pairingActive = true
                observeController()
                controller.preparePairing()
                startForegroundCompat(
                    DhdAdbPairingNotification.searchingNotification(this),
                    DhdAdbPairingNotification.SEARCHING_NOTIFICATION_ID,
                )
            }

            ACTION_SUBMIT_CODE -> {
                pairingActive = true
                observeController()
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(DhdAdbPairingNotification.REMOTE_INPUT_RESULT_KEY)
                    ?.toString()
                    ?.trim()
                if (code.isNullOrEmpty()) {
                    updateNotification(
                        DhdAdbPairingNotification.pairingServiceFoundNotification(
                            this,
                            "Enter the six-digit code shown by Android.",
                        ),
                    )
                } else {
                    updateNotification(DhdAdbPairingNotification.workingNotification(this))
                    controller.pair(code)
                }
            }

            ACTION_STOP -> {
                pairingActive = false
                controller.cancelPairing()
                stopPairingService()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        statusJob?.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeController() {
        if (statusJob != null) return
        statusJob = serviceScope.launch {
            controller.status.collectLatest { status ->
                if (!pairingActive) return@collectLatest
                when (status.state) {
                    DeveloperConnectionState.READY -> {
                        DhdAdbPairingNotification.showResult(
                            this@DhdAdbPairingService,
                            "DHD paired. Wireless Debugging can be turned off until DHD needs a restart.",
                        )
                        pairingActive = false
                        stopPairingService()
                    }

                    DeveloperConnectionState.CONNECTING,
                    DeveloperConnectionState.CHECKING,
                    -> updateNotification(DhdAdbPairingNotification.workingNotification(this@DhdAdbPairingService))

                    DeveloperConnectionState.PAIRING_REQUIRED,
                    DeveloperConnectionState.PAIRING_SEARCHING,
                    -> updateNotification(DhdAdbPairingNotification.searchingNotification(this@DhdAdbPairingService))

                    DeveloperConnectionState.PAIRING_SERVICE_FOUND ->
                        promoteToFoundNotification()

                    DeveloperConnectionState.WIRELESS_DEBUGGING_OFF ->
                        updateNotification(DhdAdbPairingNotification.searchingNotification(this@DhdAdbPairingService))

                    DeveloperConnectionState.ERROR ->
                        if (foregroundNotificationId == DhdAdbPairingNotification.FOUND_NOTIFICATION_ID) {
                            updateNotification(
                                DhdAdbPairingNotification.pairingServiceFoundNotification(
                                    this@DhdAdbPairingService,
                                    status.message,
                                ),
                            )
                        } else {
                            updateNotification(DhdAdbPairingNotification.searchingNotification(this@DhdAdbPairingService))
                        }

                    DeveloperConnectionState.UNSUPPORTED -> {
                        pairingActive = false
                        stopPairingService()
                    }
                }
            }
        }
    }

    private fun updateNotification(notification: Notification) {
        NotificationManagerCompat.from(this).notify(
            foregroundNotificationId,
            notification,
        )
    }

    private fun promoteToFoundNotification() {
        startForegroundCompat(
            DhdAdbPairingNotification.pairingServiceFoundNotification(this),
            DhdAdbPairingNotification.FOUND_NOTIFICATION_ID,
        )
    }

    private fun startForegroundCompat(notification: Notification, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(notificationId, notification)
        }
        val previousNotificationId = foregroundNotificationId
        foregroundNotificationId = notificationId
        if (previousNotificationId != notificationId) {
            NotificationManagerCompat.from(this).cancel(previousNotificationId)
        }
    }

    private fun stopPairingService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        DhdAdbPairingNotification.cancelAll(this)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.phonecontrol.assistant.action.START_ADB_PAIRING"
        const val ACTION_SUBMIT_CODE = "com.phonecontrol.assistant.action.SUBMIT_ADB_PAIRING_CODE"
        const val ACTION_STOP = "com.phonecontrol.assistant.action.STOP_ADB_PAIRING"

        fun startIntent(context: Context): Intent =
            Intent(context, DhdAdbPairingService::class.java).setAction(ACTION_START)
    }
}

/** Builds the Shizuku-style notification used only for the one-time pairing step. */
internal object DhdAdbPairingNotification {
    const val REMOTE_INPUT_RESULT_KEY = "dhd_adb_pairing_code"
    const val SEARCHING_NOTIFICATION_ID = 4207
    const val FOUND_NOTIFICATION_ID = 4211
    const val NOTIFICATION_ID = SEARCHING_NOTIFICATION_ID

    private const val CHANNEL_ID = "dhd_adb_pairing"
    private const val RESULT_NOTIFICATION_ID = 4208
    private const val REQUEST_SUBMIT_CODE = 4209
    private const val REQUEST_OPEN_APP = 4210
    private const val REQUEST_STOP_SEARCHING = 4212

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "DHD Wireless Debugging pairing",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                description = "One-time pairing input for DHD phone access"
            },
        )
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun searchingNotification(context: Context): Notification {
        createChannel(context)
        return baseBuilder(
            context = context,
            title = "Searching for pairing service",
            message = null,
        )
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop searching",
                    stopSearchingIntent(context),
                ).build(),
            )
            .build()
    }

    fun pairingServiceFoundNotification(
        context: Context,
        message: String = DEFAULT_FOUND_MESSAGE,
    ): Notification {
        createChannel(context)
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_RESULT_KEY)
            .setLabel("Six-digit pairing code")
            .build()
        val submitIntent = PendingIntent.getForegroundService(
            context,
            REQUEST_SUBMIT_CODE,
            Intent(context, DhdAdbPairingService::class.java).setAction(
                DhdAdbPairingService.ACTION_SUBMIT_CODE,
            ),
            mutablePendingIntentFlags(),
        )
        return baseBuilder(
            context = context,
            title = "Pairing service found",
            message = message,
        )
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_dialog_info,
                    "Enter pairing code",
                    submitIntent,
                )
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
            .build()
    }

    fun workingNotification(context: Context): Notification {
        createChannel(context)
        return baseBuilder(
            context = context,
            title = "Pairing DHD with Wireless Debugging",
            message = "Pairing with Wireless Debugging…",
        )
            .setOngoing(true)
            .build()
    }

    fun showResult(context: Context, message: String) {
        createChannel(context)
        NotificationManagerCompat.from(context).notify(
            RESULT_NOTIFICATION_ID,
            baseBuilder(
                context = context,
                title = "DHD pairing complete",
                message = message,
            )
                .setOngoing(false)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancel(context: Context) {
        cancelAll(context)
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).apply {
            cancel(SEARCHING_NOTIFICATION_ID)
            cancel(FOUND_NOTIFICATION_ID)
        }
    }

    fun showSearching(context: Context) {
        NotificationManagerCompat.from(context).notify(
            SEARCHING_NOTIFICATION_ID,
            searchingNotification(context),
        )
    }

    private fun baseBuilder(
        context: Context,
        title: String,
        message: String?,
    ): NotificationCompat.Builder {
        val openAppIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java),
            immutablePendingIntentFlags(),
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .also { builder ->
                if (!message.isNullOrBlank()) {
                    builder
                        .setContentText(message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                }
            }
    }

    private fun stopSearchingIntent(context: Context): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            REQUEST_STOP_SEARCHING,
            Intent(context, DhdAdbPairingService::class.java).setAction(
                DhdAdbPairingService.ACTION_STOP,
            ),
            immutablePendingIntentFlags(),
        )

    private fun mutablePendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }

    private fun immutablePendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

    private const val DEFAULT_FOUND_MESSAGE =
        "Enter the pairing code shown by Android."
}
