package com.phonecontrol.assistant.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.phonecontrol.assistant.PhoneControlApplication
import com.phonecontrol.assistant.apps.InstalledAppsRepository
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID

data class AssistantColorScheme(
    val isDark: Boolean,
    val background: Color,
    val surfaceCard: Color,
    val composerBackground: Color,
    val borderColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val accentBlue: Color,
    val accentGreen: Color,
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
    surfaceCard = Color(0xFF212121),
    composerBackground = Color(0xFF212121),
    borderColor = Color(0xFF2C2C2E),
    textPrimary = Color(0xFFECECEC),
    textSecondary = Color(0xFF8E8E93),
    userBubble = Color(0xFF1B2D4B),
    userBubbleText = Color.White,
    accentBlue = Color(0xFF2C67C5), // Specified #2C67C5
    accentGreen = Color(0xFF10A37F),
    errorRed = Color(0xFFEF4444),
    warningAmber = Color(0xFFF59E0B),
    sendButtonActiveBg = Color.White,
    sendButtonActiveIcon = Color.Black,
    sendButtonInactiveBg = Color(0xFF333333),
    sendButtonInactiveIcon = Color(0xFF8E8E93),
)

// OpenAI ChatGPT Light Theme
val LightAssistantColors = AssistantColorScheme(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surfaceCard = Color(0xFFF4F4F5),
    composerBackground = Color(0xFFF4F4F5),
    borderColor = Color(0xFFE5E7EB),
    textPrimary = Color(0xFF0D0D0D),
    textSecondary = Color(0xFF6B7280),
    userBubble = Color(0xFFE5E7EB),
    userBubbleText = Color(0xFF0D0D0D),
    accentBlue = Color(0xFF2C67C5),
    accentGreen = Color(0xFF10A37F),
    errorRed = Color(0xFFDC2626),
    warningAmber = Color(0xFFD97706),
    sendButtonActiveBg = Color(0xFF0D0D0D),
    sendButtonActiveIcon = Color.White,
    sendButtonInactiveBg = Color(0xFFE5E7EB),
    sendButtonInactiveIcon = Color(0xFF9CA3AF),
)

val LocalAssistantColors = staticCompositionLocalOf { DarkAssistantColors }

private const val PREFS_NAME = "dhd_ui_preferences"
private const val KEY_DARK_MODE = "pref_is_dark_mode"

enum class AssistantNavigationScreen {
    MAIN,
    SETTINGS,
    APPROVED_APPS,
}

@Composable
fun PhoneControlApp(
    initialConversationId: String? = null,
    onRunRequest: (String, String?) -> Unit,
    onStopSession: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var isDarkMode by rememberSaveable {
        mutableStateOf(prefs.getBoolean(KEY_DARK_MODE, true))
    }

    val setDarkMode: (Boolean) -> Unit = { enabled ->
        isDarkMode = enabled
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply()
    }

    val application = context.applicationContext as PhoneControlApplication
    val coordinator = application.sessionCoordinator
    val conversationStore = application.conversationStore
    val permissions = application.appPermissionRepository
    val shizukuController = application.shizukuController
    val shizukuStatus by shizukuController.status.collectAsState()
    val apps = remember { InstalledAppsRepository(context).listLaunchableUserApps() }
    var currentScreen by rememberSaveable { mutableStateOf(AssistantNavigationScreen.MAIN) }

    // Intercept hardware / system back button to navigate back
    BackHandler(enabled = currentScreen != AssistantNavigationScreen.MAIN) {
        currentScreen = when (currentScreen) {
            AssistantNavigationScreen.APPROVED_APPS -> AssistantNavigationScreen.SETTINGS
            AssistantNavigationScreen.SETTINGS -> AssistantNavigationScreen.MAIN
            AssistantNavigationScreen.MAIN -> AssistantNavigationScreen.MAIN
        }
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

    CompositionLocalProvider(LocalAssistantColors provides assistantColors) {
        MaterialTheme(colorScheme = materialColors) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = assistantColors.background,
            ) {
                when (currentScreen) {
                    AssistantNavigationScreen.MAIN -> {
                        AssistantScreen(
                            store = conversationStore,
                            coordinator = coordinator,
                            initialConversationId = initialConversationId,
                            onRunRequest = onRunRequest,
                            onStopSession = onStopSession,
                            onOpenSettings = { currentScreen = AssistantNavigationScreen.SETTINGS },
                            onStartFresh = {
                                conversationStore.deleteConversation(DHD_CONVERSATION_ID)
                            },
                        )
                    }
                    AssistantNavigationScreen.SETTINGS -> {
                        SettingsScreen(
                            apps = apps,
                            permissions = permissions,
                            shizukuStatus = shizukuStatus,
                            isDarkMode = isDarkMode,
                            onToggleDarkMode = setDarkMode,
                            onRequestShizukuPermission = { shizukuController.requestPermission() },
                            onRefreshShizuku = shizukuController::refresh,
                            onOpenApprovedApps = { currentScreen = AssistantNavigationScreen.APPROVED_APPS },
                            onBack = { currentScreen = AssistantNavigationScreen.MAIN },
                        )
                    }
                    AssistantNavigationScreen.APPROVED_APPS -> {
                        ApprovedAppsScreen(
                            apps = apps,
                            permissions = permissions,
                            onBack = { currentScreen = AssistantNavigationScreen.SETTINGS },
                        )
                    }
                }
            }
        }
    }
}
