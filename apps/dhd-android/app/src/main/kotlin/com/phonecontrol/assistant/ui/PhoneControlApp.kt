package com.phonecontrol.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.Surface as AndroidSurface
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.phonecontrol.assistant.PhoneControlApplication
import com.phonecontrol.assistant.apps.InstalledAppsRepository
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID
import com.phonecontrol.assistant.domain.ReasoningEffort

data class AssistantColorScheme(
    val isDark: Boolean,
    val background: Color,
    val surfaceCard: Color,
    val settingsCard: Color,
    val cardDivider: Color,
    val composerBackground: Color,
    val borderColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val accentBlue: Color,
    val accentGreen: Color,
    val accentPurple: Color,
    val accentGold: Color,
    val accentOrange: Color,
    val accentPink: Color,
    val errorRed: Color,
    val warningAmber: Color,
    val sendButtonActiveBg: Color,
    val sendButtonActiveIcon: Color,
    val sendButtonInactiveBg: Color,
    val sendButtonInactiveIcon: Color,
)

// OpenAI ChatGPT Dark Theme with user-specified cobalt blue #2C67C5
val DarkAssistantColors = AssistantColorScheme(
    isDark = true,
    background = Color(0xFF000000),
    surfaceCard = Color(0xFF1C1C1E),
    settingsCard = Color(0xFF484848),
    cardDivider = Color(0xFF000000),
    composerBackground = Color(0xFF212121),
    borderColor = Color(0xFF2C2C2E),
    textPrimary = Color(0xFFECECEC),
    textSecondary = Color(0xFF8E8E93),
    userBubble = Color(0xFF1B2D4B),
    userBubbleText = Color.White,
    accentBlue = Color(0xFF2C67C5), // Specified #2C67C5
    accentGreen = Color(0xFF10A37F),
    accentPurple = Color(0xFFB38CFF),
    accentGold = Color(0xFFFACC15),
    accentOrange = Color(0xFFFB923C),
    accentPink = Color(0xFFF472B6),
    errorRed = Color(0xFFEF4444),
    warningAmber = Color(0xFFF59E0B),
    sendButtonActiveBg = Color(0xFF2C67C5), // App blue
    sendButtonActiveIcon = Color.White,
    sendButtonInactiveBg = Color(0xFF333333),
    sendButtonInactiveIcon = Color(0xFF8E8E93),
)

// OpenAI ChatGPT Light Theme
val LightAssistantColors = AssistantColorScheme(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surfaceCard = Color(0xFFF4F4F5),
    settingsCard = Color(0xFFF4F4F5),
    cardDivider = Color(0xFFE5E7EB),
    composerBackground = Color(0xFFF4F4F5),
    borderColor = Color(0xFFE5E7EB),
    textPrimary = Color(0xFF0D0D0D),
    textSecondary = Color(0xFF6B7280),
    userBubble = Color(0xFFE5E7EB),
    userBubbleText = Color(0xFF0D0D0D),
    accentBlue = Color(0xFF2C67C5),
    accentGreen = Color(0xFF10A37F),
    accentPurple = Color(0xFF7C3AED),
    accentGold = Color(0xFFA16207),
    accentOrange = Color(0xFFC2410C),
    accentPink = Color(0xFFBE185D),
    errorRed = Color(0xFFDC2626),
    warningAmber = Color(0xFFD97706),
    sendButtonActiveBg = Color(0xFF2C67C5), // App blue
    sendButtonActiveIcon = Color.White,
    sendButtonInactiveBg = Color(0xFFE5E7EB),
    sendButtonInactiveIcon = Color(0xFF9CA3AF),
)

val LocalAssistantColors = staticCompositionLocalOf { DarkAssistantColors }

private const val PREFS_NAME = "dhd_ui_preferences"
private const val KEY_THEME_MODE = "pref_theme_mode"
private const val KEY_REASONING_EFFORT = "pref_reasoning_effort"
private const val KEY_VISIBLE_REASONING_EFFORTS = "pref_visible_reasoning_efforts"
private const val KEY_FAST_MODE = "pref_fast_mode"

enum class ThemeMode(val storageValue: String, val label: String) {
    SYSTEM("system", "System (Default)"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: DARK
    }
}

private fun visibleReasoningEffortsFromStorage(value: String?): List<ReasoningEffort> {
    val storedValues = value
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        .orEmpty()
    val configured = ReasoningEffort.entries.filter { it.storageValue in storedValues }
    return configured.ifEmpty { ReasoningEffort.entries }
}

object AppRoutes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val TASK_DISPLAYS = "task_displays"
    const val PAIRING = "pairing"
    const val APPROVED_APPS = "approved_apps"
    const val COMPANION = "companion"
}

@Composable
fun PhoneControlApp(
    initialConversationId: String? = null,
    onRunRequest: (String, String?, String?, Boolean) -> Unit,
    onStopSession: () -> Unit,
    onAcknowledgeAttention: () -> Boolean,
    onSteerRequest: (String) -> Boolean,
    previewState: LiveDisplayPreviewState? = null,
    onPreviewSurfaceAvailable: (AndroidSurface) -> Unit = {},
    onPreviewSurfaceDestroyed: (AndroidSurface) -> Unit = {},
    /** Display records supplied by the lifecycle/backend layer. */
    displayRecords: List<TaskDisplayUiRecord> = emptyList(),
    onTaskDisplaySurfaceAvailable: (TaskDisplayUiRecord, AndroidSurface) -> Unit = { _, surface ->
        onPreviewSurfaceAvailable(surface)
    },
    onTaskDisplaySurfaceDestroyed: (TaskDisplayUiRecord, AndroidSurface) -> Unit = { _, surface ->
        onPreviewSurfaceDestroyed(surface)
    },
    onEndTaskDisplay: (TaskDisplayUiRecord) -> Unit = {},
    onRetryTaskDisplayPreview: (TaskDisplayUiRecord) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val isSystemDark = isSystemInDarkTheme()
    var themeMode by rememberSaveable {
        mutableStateOf(ThemeMode.fromStorage(prefs.getString(KEY_THEME_MODE, "dark")))
    }
    var reasoningEffortValue by rememberSaveable {
        mutableStateOf(
            ReasoningEffort.fromStorage(
                prefs.getString(KEY_REASONING_EFFORT, ReasoningEffort.default.storageValue),
            ).storageValue,
        )
    }
    var visibleReasoningEffortValues by rememberSaveable {
        mutableStateOf(
            visibleReasoningEffortsFromStorage(
                prefs.getString(KEY_VISIBLE_REASONING_EFFORTS, null),
            ).map(ReasoningEffort::storageValue),
        )
    }
    var fastMode by rememberSaveable {
        mutableStateOf(prefs.getBoolean(KEY_FAST_MODE, false))
    }

    val isDarkMode = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val setThemeMode: (ThemeMode) -> Unit = { mode ->
        themeMode = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.storageValue).apply()
    }
    val visibleReasoningEfforts = visibleReasoningEffortsFromStorage(
        visibleReasoningEffortValues.joinToString(","),
    )
    val storedReasoningEffort = ReasoningEffort.fromStorage(reasoningEffortValue)
    val reasoningEffort = storedReasoningEffort.takeIf { it in visibleReasoningEfforts }
        ?: visibleReasoningEfforts.first()
    LaunchedEffect(visibleReasoningEfforts, reasoningEffortValue) {
        if (reasoningEffortValue != reasoningEffort.storageValue) {
            reasoningEffortValue = reasoningEffort.storageValue
            prefs.edit().putString(KEY_REASONING_EFFORT, reasoningEffort.storageValue).apply()
        }
    }
    val setReasoningEffort: (ReasoningEffort) -> Unit = { effort ->
        if (effort in visibleReasoningEfforts) {
            reasoningEffortValue = effort.storageValue
            prefs.edit().putString(KEY_REASONING_EFFORT, effort.storageValue).apply()
        }
    }
    val setFastMode: (Boolean) -> Unit = { enabled ->
        fastMode = enabled
        prefs.edit().putBoolean(KEY_FAST_MODE, enabled).apply()
    }
    val setReasoningEffortVisibility: (ReasoningEffort, Boolean) -> Unit = { effort, visible ->
        val current = visibleReasoningEfforts.toSet()
        val next = if (visible) current + effort else current - effort
        if (next.isNotEmpty()) {
            val ordered = ReasoningEffort.entries.filter { it in next }
            visibleReasoningEffortValues = ordered.map(ReasoningEffort::storageValue)
            prefs.edit()
                .putString(KEY_VISIBLE_REASONING_EFFORTS, ordered.joinToString(",") { it.storageValue })
                .apply()
            if (reasoningEffort !in ordered) {
                val fallback = ordered.first()
                reasoningEffortValue = fallback.storageValue
                prefs.edit().putString(KEY_REASONING_EFFORT, fallback.storageValue).apply()
            }
        }
    }

    val application = context.applicationContext as PhoneControlApplication
    val coordinator = application.sessionCoordinator
    val conversationStore = application.conversationStore
    val permissions = application.appPermissionRepository
    val developerModeController = application.developerModeController
    val developerStatus by developerModeController.status.collectAsState()
    val companionConnected by application.devBridgeServer.companionConnected.collectAsState()
    val apps = remember { InstalledAppsRepository(context).listLaunchableUserApps() }

    val navController = rememberNavController()
    var viewerSessionKey by rememberSaveable { mutableStateOf<String?>(null) }
    var taskDisplaysSheetVisible by rememberSaveable { mutableStateOf(false) }
    val openTaskDisplays: () -> Unit = { taskDisplaysSheetVisible = true }

    // Until the backend exposes its registry, the active preview remains a
    // valid single-record manager model. MainActivity can pass persisted and
    // retained records later without changing the viewer contract.
    val visibleDisplayRecords = displayRecords.ifEmpty {
        previewState?.sessionKey?.let { key ->
            listOf(
                TaskDisplayUiRecord(
                    sessionKey = key,
                    lifecycle = TaskDisplayLifecycle.RUNNING,
                    appLabel = previewState.appLabel,
                    currentPurpose = previewState.purpose,
                    currentToolName = previewState.currentToolName,
                    previewState = previewState,
                ),
            )
        }.orEmpty()
    }
    val viewerRecord = viewerSessionKey?.let { key ->
        visibleDisplayRecords.firstOrNull { it.sessionKey == key }
    }
    val viewerState = viewerRecord?.previewState
        ?: previewState?.takeIf { it.sessionKey == viewerRecord?.sessionKey }
        ?: viewerRecord?.let { record ->
            val ratio = record.geometry?.let { geometry ->
                geometry.width.toFloat() / geometry.height.toFloat()
            } ?: DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO
            LiveDisplayPreviewState.unavailable(
                message = record.error,
                aspectRatio = ratio,
                sessionKey = record.sessionKey,
            ).copy(
                appLabel = record.appLabel,
                purpose = record.currentPurpose,
                currentToolName = record.currentToolName,
            )
        }

    val assistantColors = if (isDarkMode) DarkAssistantColors else LightAssistantColors
    val materialColors = if (isDarkMode) {
        darkColorScheme(
            primary = assistantColors.accentBlue,
            onPrimary = Color.White,
            secondary = assistantColors.accentGreen,
            background = assistantColors.background,
            surface = assistantColors.surfaceCard,
            onBackground = assistantColors.textPrimary,
            onSurface = assistantColors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = assistantColors.accentBlue,
            onPrimary = Color.White,
            secondary = assistantColors.accentGreen,
            background = assistantColors.background,
            surface = assistantColors.surfaceCard,
            onBackground = assistantColors.textPrimary,
            onSurface = assistantColors.textPrimary,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDarkMode
            insetsController.isAppearanceLightNavigationBars = !isDarkMode
        }
    }

    CompositionLocalProvider(LocalAssistantColors provides assistantColors) {
        MaterialTheme(colorScheme = materialColors) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = assistantColors.background,
            ) {
                NavHost(
                    navController = navController,
                    startDestination = AppRoutes.MAIN,
                    enterTransition = {
                        slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(280)) + fadeIn(animationSpec = tween(280))
                    },
                    exitTransition = {
                        slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(280)) + fadeOut(animationSpec = tween(280))
                    },
                    popEnterTransition = {
                        slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(280)) + fadeIn(animationSpec = tween(280))
                    },
                    popExitTransition = {
                        slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(280)) + fadeOut(animationSpec = tween(280))
                    },
                ) {
                    composable(AppRoutes.MAIN) {
                        AssistantScreen(
                            store = conversationStore,
                            coordinator = coordinator,
                            initialConversationId = initialConversationId,
                            onRunRequest = onRunRequest,
                            reasoningEffort = reasoningEffort,
                            visibleReasoningEfforts = visibleReasoningEfforts,
                            onSelectReasoningEffort = setReasoningEffort,
                            fastMode = fastMode,
                            onSetFastMode = setFastMode,
                            onStopSession = onStopSession,
                            onAcknowledgeAttention = onAcknowledgeAttention,
                            onSteerRequest = onSteerRequest,
                            onOpenSettings = { navController.navigate(AppRoutes.SETTINGS) },
                            onOpenTaskDisplays = openTaskDisplays,
                            onStartFresh = {
                                conversationStore.deleteConversation(DHD_CONVERSATION_ID)
                            },
                            developerStatus = developerStatus,
                            companionConnected = companionConnected,
                            onOpenDeveloperOptions = {
                                openDeveloperOptions(context)
                            },
                            onOpenCompanion = { navController.navigate(AppRoutes.COMPANION) },
                            previewState = previewState,
                            onPreviewSurfaceAvailable = onPreviewSurfaceAvailable,
                            onPreviewSurfaceDestroyed = onPreviewSurfaceDestroyed,
                            onOpenPreview = { sessionKey -> viewerSessionKey = sessionKey },
                            expandedPreviewSessionKey = viewerSessionKey,
                        )
                    }

                    composable(AppRoutes.SETTINGS) {
                        SettingsScreen(
                            apps = apps,
                            permissions = permissions,
                            developerStatus = developerStatus,
                            bridgeServer = application.devBridgeServer,
                            themeMode = themeMode,
                            onSelectThemeMode = setThemeMode,
                            visibleReasoningEfforts = visibleReasoningEfforts,
                            onSetReasoningEffortVisibility = setReasoningEffortVisibility,
                            onOpenPairing = { navController.navigate(AppRoutes.PAIRING) },
                            onOpenApprovedApps = { navController.navigate(AppRoutes.APPROVED_APPS) },
                            onOpenCompanion = { navController.navigate(AppRoutes.COMPANION) },
                            onOpenTaskDisplays = openTaskDisplays,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    // Keep the route for state restoration and older callers;
                    // the screen itself is a bottom sheet rather than a full
                    // page, so it retains the same presentation everywhere.
                    composable(AppRoutes.TASK_DISPLAYS) {
                        TaskDisplaysScreen(
                            records = visibleDisplayRecords,
                            onView = { record ->
                                viewerSessionKey = record.sessionKey
                                navController.popBackStack()
                            },
                            onEnd = onEndTaskDisplay,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(AppRoutes.PAIRING) {
                        PairingScreen(
                            status = developerStatus,
                            onStartPairingNotification = { developerModeController.startPairingNotification() },
                            onOpenDeveloperOptions = { openDeveloperOptions(context) },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(AppRoutes.COMPANION) {
                        CompanionScreen(
                            bridgeServer = application.devBridgeServer,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(AppRoutes.APPROVED_APPS) {
                        ApprovedAppsScreen(
                            apps = apps,
                            permissions = permissions,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }

                if (taskDisplaysSheetVisible) {
                    TaskDisplaysScreen(
                        records = visibleDisplayRecords,
                        onView = { record ->
                            taskDisplaysSheetVisible = false
                            viewerSessionKey = record.sessionKey
                        },
                        onEnd = onEndTaskDisplay,
                        onBack = { taskDisplaysSheetVisible = false },
                    )
                }

                if (viewerRecord != null && viewerState != null) {
                    FullScreenLiveDisplayViewer(
                        record = viewerRecord,
                        state = viewerState,
                        onDismiss = { viewerSessionKey = null },
                        onSurfaceAvailable = { surface ->
                            onTaskDisplaySurfaceAvailable(viewerRecord, surface)
                        },
                        onSurfaceDestroyed = { surface ->
                            onTaskDisplaySurfaceDestroyed(viewerRecord, surface)
                        },
                        onRetry = { onRetryTaskDisplayPreview(viewerRecord) },
                        onAcknowledgeAttention = onAcknowledgeAttention,
                        onStopSession = onStopSession,
                    )
                }
            }
        }
    }
}

private fun openDeveloperOptions(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            },
        )
    }
}
