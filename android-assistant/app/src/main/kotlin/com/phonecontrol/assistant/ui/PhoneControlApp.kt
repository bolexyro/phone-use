package com.phonecontrol.assistant.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.phonecontrol.assistant.PhoneControlApplication
import com.phonecontrol.assistant.apps.InstalledAppsRepository

@Composable
fun PhoneControlApp(
    initialConversationId: String? = null,
    onRunRequest: (String, String?) -> Unit,
    onStopSession: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as PhoneControlApplication
    val coordinator = application.sessionCoordinator
    val conversationStore = application.conversationStore
    val permissions = application.appPermissionRepository
    val shizukuController = application.shizukuController
    val shizukuStatus by shizukuController.status.collectAsState()
    val apps = remember { InstalledAppsRepository(context).listLaunchableUserApps() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    MaterialTheme(colorScheme = AssistantColors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (showSettings) {
                SettingsScreen(
                    apps = apps,
                    permissions = permissions,
                    shizukuStatus = shizukuStatus,
                    onRequestShizukuPermission = { shizukuController.requestPermission() },
                    onRefreshShizuku = shizukuController::refresh,
                    onBack = { showSettings = false },
                )
            } else {
                AssistantScreen(
                    store = conversationStore,
                    coordinator = coordinator,
                    initialConversationId = initialConversationId,
                    onRunRequest = onRunRequest,
                    onStopSession = onStopSession,
                    onOpenSettings = { showSettings = true },
                )
            }
        }
    }
}

private val AssistantColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF006C4C),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = androidx.compose.ui.graphics.Color(0xFF4D6359),
    tertiary = androidx.compose.ui.graphics.Color(0xFF416277),
    background = androidx.compose.ui.graphics.Color(0xFFF8FBF7),
    surface = androidx.compose.ui.graphics.Color(0xFFF8FBF7),
)
