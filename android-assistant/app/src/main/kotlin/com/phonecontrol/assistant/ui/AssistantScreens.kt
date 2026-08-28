@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.shizuku.ShizukuStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun AssistantScreen(
    coordinator: SessionCoordinator,
    onOpenSettings: () -> Unit,
    onRunRequest: (String) -> Unit,
    onStopSession: () -> Unit,
) {
    var request by rememberSaveable { mutableStateOf("") }
    val state by coordinator.state.collectAsState()
    val events by coordinator.events.collectAsState()
    val active = state is SessionState.Running || state is SessionState.Paused

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone Control Assistant") },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Watch mode", style = MaterialTheme.typography.headlineSmall)
            Text(
                "The assistant operates the phone’s main display in Watch mode. Start the desktop Codex companion, then watch the approved app move live.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SessionStatusCard(
                state = state,
                onPauseResume = coordinator::togglePause,
                onStop = onStopSession,
            )

            OutlinedTextField(
                value = request,
                onValueChange = { request = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !active,
                label = { Text("What should the assistant do?") },
                placeholder = { Text("e.g. Open Spotify and search for…") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                ),
            )
            Button(
                onClick = {
                    onRunRequest(request.trim())
                    request = ""
                },
                enabled = request.isNotBlank() && !active,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Conversation", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${events.size} events",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Tool steps are summarized here; private Codex reasoning is not shown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActivityTimeline(events = events, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SessionStatusCard(
    state: SessionState,
    onPauseResume: () -> Boolean,
    onStop: () -> Unit,
) {
    val (title, detail) = when (state) {
        SessionState.Idle -> "Ready" to "Type a request to start a session."
        is SessionState.Running -> "Running" to state.currentPurpose
        is SessionState.Paused -> "Paused" to state.currentPurpose
        is SessionState.Stopped -> "Stopped" to state.reason
        is SessionState.Completed -> "Completed" to state.message
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (state is SessionState.Completed) 4 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            if (state is SessionState.Running || state is SessionState.Paused) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPauseResume() }) {
                        Text(if (state is SessionState.Paused) "Resume" else "Pause")
                    }
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                }
            }
        }
    }
}

@Composable
private fun ActivityTimeline(
    events: List<ActivityEvent>,
    modifier: Modifier = Modifier,
) {
    if (events.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
            Text(
                "No activity yet. The timeline will show safe, user-facing actions and confirmations here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(events.reversed(), key = { it.id }) { event ->
            ActivityEventRow(event)
        }
    }
}

@Composable
private fun ActivityEventRow(event: ActivityEvent) {
    val markerColor = when (event.kind) {
        ActivityEventKind.ACTION_FAILED,
        ActivityEventKind.ATTENTION_REQUIRED -> MaterialTheme.colorScheme.error
        ActivityEventKind.CONFIRMATION_REQUIRED -> MaterialTheme.colorScheme.tertiary
        ActivityEventKind.AGENT_MESSAGE -> MaterialTheme.colorScheme.primary
        ActivityEventKind.ACTION_SUCCEEDED,
        ActivityEventKind.SESSION_COMPLETED -> Color(0xFF16724A)
        else -> MaterialTheme.colorScheme.primary
    }
    val cardColor = when (event.kind) {
        ActivityEventKind.AGENT_MESSAGE -> MaterialTheme.colorScheme.primaryContainer
        ActivityEventKind.ATTENTION_REQUIRED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                color = markerColor,
                contentColor = Color.White,
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(eventBadge(event), style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(event.message, style = MaterialTheme.typography.bodyMedium)
                event.actionType?.let { actionType ->
                    Text(
                        actionTypeLabel(actionType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestampEpochMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun eventBadge(event: ActivityEvent): String = when (event.kind) {
    ActivityEventKind.AGENT_MESSAGE -> "AI"
    ActivityEventKind.ATTENTION_REQUIRED -> "!"
    ActivityEventKind.CONFIRMATION_REQUIRED -> "?"
    ActivityEventKind.ACTION_SUCCEEDED -> "✓"
    ActivityEventKind.ACTION_FAILED -> "×"
    ActivityEventKind.ACTION_STARTED -> "…"
    ActivityEventKind.ACTION_PROPOSED -> "→"
    ActivityEventKind.SESSION_COMPLETED -> "✓"
    ActivityEventKind.SESSION_STARTED -> "▶"
    ActivityEventKind.SESSION_PAUSED -> "Ⅱ"
    ActivityEventKind.SESSION_RESUMED -> "▶"
    ActivityEventKind.SESSION_STOPPED -> "■"
    ActivityEventKind.SYSTEM -> "•"
}

private fun actionTypeLabel(actionType: com.phonecontrol.assistant.domain.ActionType): String = when (actionType) {
    com.phonecontrol.assistant.domain.ActionType.OPEN_APP -> "📱 Open app"
    com.phonecontrol.assistant.domain.ActionType.TAP -> "👆 Tap"
    com.phonecontrol.assistant.domain.ActionType.TYPE -> "⌨ Type"
    com.phonecontrol.assistant.domain.ActionType.SWIPE -> "↕ Swipe"
    com.phonecontrol.assistant.domain.ActionType.SCROLL -> "↕ Scroll"
    com.phonecontrol.assistant.domain.ActionType.BACK -> "← Back"
    com.phonecontrol.assistant.domain.ActionType.KEYPRESS -> "⌨ Keypress"
    com.phonecontrol.assistant.domain.ActionType.WAIT -> "◷ Wait"
}

@Composable
fun SettingsScreen(
    apps: List<InstalledUserApp>,
    permissions: AppPermissionRepository,
    shizukuStatus: ShizukuStatus,
    onRequestShizukuPermission: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onBack: () -> Unit,
) {
    var enabledPackages by rememberSaveable { mutableStateOf(permissions.enabledPackages()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("Shizuku", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    shizukuStatus.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "The desktop Codex bridge can request typed open, tap, type, swipe, scroll, keypress, back, and wait actions. We still fail closed when the screen or Shizuku capability cannot be verified on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (shizukuStatus.binderAvailable && !shizukuStatus.permissionGranted) {
                        Button(onClick = onRequestShizukuPermission) { Text("Request permission") }
                    }
                    OutlinedButton(onClick = onRefreshShizuku) { Text("Refresh") }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                Spacer(Modifier.height(8.dp))
                Text("Approved apps", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Every app starts disabled. Enable only the apps this assistant is allowed to operate. There is no global enable-all switch.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
            }
            if (apps.isEmpty()) {
                item {
                    Text(
                        "No launchable user apps were found.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(apps, key = { it.packageName }) { app ->
                    val enabled = app.packageName in enabledPackages
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, fontWeight = FontWeight.Medium)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { checked ->
                                    permissions.setEnabled(app.packageName, checked)
                                    enabledPackages = permissions.enabledPackages()
                                },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
