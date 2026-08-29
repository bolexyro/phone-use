@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.domain.userFacingActivityLabel
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.shizuku.ShizukuStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun AssistantScreen(
    store: ConversationStore,
    coordinator: SessionCoordinator,
    @Suppress("UNUSED_PARAMETER") initialConversationId: String?,
    onRunRequest: (String, String?) -> Unit,
    onStopSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartFresh: () -> Unit,
) {
    val state by coordinator.state.collectAsState()
    val timeline by store.timeline(DHD_CONVERSATION_ID).collectAsState()
    val active = state.isActive()
    var showStartFreshConfirmation by rememberSaveable { mutableStateOf(false) }
    val recentCutoff = System.currentTimeMillis() - RECENT_HISTORY_WINDOW_MS
    val recentTimeline = timeline.filter { item ->
        item.timestampEpochMs >= recentCutoff ||
            (active && item.belongsTo(state.sessionIdOrNullForUi()))
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DHD", maxLines = 1)
                        Text(
                            "Your phone assistant",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showStartFreshConfirmation = true },
                        enabled = !active,
                    ) {
                        Text("Start fresh")
                    }
                    IconButton(onClick = onOpenSettings) { Text("⚙") }
                },
            )
        },
        bottomBar = {
            RequestComposer(
                enabled = !active,
                onSend = { request -> onRunRequest(request, DHD_CONVERSATION_ID) },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            Text(
                "Recent activity · 24 hours",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
            if (recentTimeline.isEmpty()) {
                EmptyChat(
                    modifier = Modifier.weight(1f),
                    message = if (timeline.isEmpty()) {
                        "Ask DHD to work in an app you have approved."
                    } else {
                        "No activity in the last 24 hours."
                    },
                )
            } else {
                ConversationTimeline(
                    timeline = recentTimeline,
                    state = state,
                    modifier = Modifier.weight(1f),
                    onTogglePause = coordinator::togglePause,
                    onStopSession = onStopSession,
                )
            }
        }
    }

    if (showStartFreshConfirmation) {
        AlertDialog(
            onDismissRequest = { showStartFreshConfirmation = false },
            title = { Text("Start fresh?") },
            text = {
                Text(
                    "This clears the DHD timeline and forgets its stored Codex thread " +
                        "binding. App permissions and Shizuku stay unchanged.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStartFreshConfirmation = false
                        onStartFresh()
                    },
                ) {
                    Text("Start fresh")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartFreshConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun EmptyChat(
    modifier: Modifier = Modifier,
    message: String = "Ask DHD to work in an app you have approved.",
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("What can I do on your phone?", style = MaterialTheme.typography.headlineSmall)
            Text(
                "$message You will see each purposeful step as it happens.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private data class TaskGroup(
    val id: String,
    val userMessage: TimelineItem.Message?,
    val activities: List<TimelineItem.Activity>,
    val assistantMessages: List<TimelineItem.Message>,
    val timestampEpochMs: Long,
)

private class TaskGroupBuilder(val id: String) {
    var userMessage: TimelineItem.Message? = null
    val activities = mutableListOf<TimelineItem.Activity>()
    val assistantMessages = mutableListOf<TimelineItem.Message>()
    var timestampEpochMs: Long = Long.MAX_VALUE

    fun addTimestamp(timestamp: Long) {
        timestampEpochMs = minOf(timestampEpochMs, timestamp)
    }

    fun build(): TaskGroup = TaskGroup(
        id = id,
        userMessage = userMessage,
        activities = activities.toList(),
        assistantMessages = assistantMessages.toList(),
        timestampEpochMs = timestampEpochMs,
    )
}

private fun groupTimeline(timeline: List<TimelineItem>): List<TaskGroup> {
    val builders = linkedMapOf<String, TaskGroupBuilder>()
    timeline.forEach { item ->
        val key = when (item) {
            is TimelineItem.Message -> item.runId ?: "message-${item.id}"
            is TimelineItem.Activity -> item.runId
        }
        val builder = builders.getOrPut(key) { TaskGroupBuilder(key) }
        builder.addTimestamp(item.timestampEpochMs)
        when (item) {
            is TimelineItem.Message -> {
                if (item.role == "user" && builder.userMessage == null) {
                    builder.userMessage = item
                } else {
                    builder.assistantMessages += item
                }
            }
            is TimelineItem.Activity -> builder.activities += item
        }
    }
    return builders.values.map(TaskGroupBuilder::build).sortedBy { it.timestampEpochMs }
}

@Composable
private fun ConversationTimeline(
    timeline: List<TimelineItem>,
    state: SessionState,
    modifier: Modifier = Modifier,
    onTogglePause: () -> Boolean,
    onStopSession: () -> Unit,
) {
    val listState = rememberLazyListState()
    val groups = remember(timeline) { groupTimeline(timeline) }
    LaunchedEffect(groups.lastOrNull()?.id, groups.lastOrNull()?.activities?.size) {
        if (groups.isNotEmpty()) listState.animateScrollToItem(groups.lastIndex)
    }
    if (groups.isEmpty()) {
        EmptyChat(modifier)
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(groups, key = { it.id }) { group ->
            val active = state.isActive() && state.sessionIdOrNullForUi() == group.id
            TaskGroupCard(group = group, active = active)
        }
        if (state.isActive()) {
            item(key = "active-run-controls") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { onTogglePause() }) {
                        Text(if (state is SessionState.Paused) "Resume" else "Pause")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onStopSession) { Text("Stop task") }
                }
            }
        }
    }
}

@Composable
private fun TaskGroupCard(group: TaskGroup, active: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        group.userMessage?.let { MessageBubble(it) }
        if (group.activities.isNotEmpty()) {
            ActivityStack(
                stackKey = group.id,
                activities = group.activities,
                active = active,
            )
        }
        group.assistantMessages.forEach { MessageBubble(it) }
    }
}

@Composable
private fun MessageBubble(message: TimelineItem.Message) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.94f),
        ) {
            if (!isUser) {
                Text("DHD", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.padding(top = if (isUser) 0.dp else 4.dp),
            ) {
                Text(message.text, modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp))
            }
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestampEpochMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun ActivityStack(
    stackKey: String,
    activities: List<TimelineItem.Activity>,
    active: Boolean,
) {
    var expanded by rememberSaveable(stackKey) { mutableStateOf(active) }
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        expanded = active
    }
    LaunchedEffect(activities.size, active) {
        if (active && activities.isNotEmpty()) {
            listState.animateScrollToItem(activities.lastIndex)
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_connected_nodes),
                    contentDescription = "DHD activity",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(
                        if (active) "Working · ${activities.size} actions" else "${activities.size} actions",
                        fontWeight = FontWeight.SemiBold,
                    )
                    activities.lastOrNull()?.let { latest ->
                        Text(
                            activityLabel(latest),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(if (expanded) "⌃" else "⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(214.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(activities, key = { it.id }) { activity ->
                        CompactActivityRow(activity)
                    }
                }
            } else {
                activities.lastOrNull()?.let { CompactActivityRow(it, allowDetails = false) }
            }
        }
    }
}

@Composable
private fun CompactActivityRow(
    activity: TimelineItem.Activity,
    allowDetails: Boolean = true,
) {
    var detailsExpanded by rememberSaveable(activity.id) { mutableStateOf(false) }
    val statusColor = activityStatusColor(activity.status)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = allowDetails) { detailsExpanded = !detailsExpanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                statusGlyph(activity.status),
                color = statusColor,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 1.dp),
            )
            Column(modifier = Modifier.padding(start = 9.dp).weight(1f)) {
                Text(
                    activityLabel(activity),
                    maxLines = if (detailsExpanded) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                    Text(activityStatusText(activity.status), color = statusColor, style = MaterialTheme.typography.labelSmall)
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(activity.timestampEpochMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (detailsExpanded) {
            activity.targetDescription?.takeIf(String::isNotBlank)?.let {
                Text(
                    "Target: $it",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 25.dp, top = 6.dp),
                )
            }
            activity.message.takeIf(String::isNotBlank)?.let {
                Text(
                    "Why: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 25.dp, top = 3.dp),
                )
            }
        }
    }
}

private fun activityLabel(activity: TimelineItem.Activity): String = userFacingActivityLabel(
    actionType = activity.actionType?.let { runCatching { com.phonecontrol.assistant.domain.ActionType.valueOf(it) }.getOrNull() },
    purpose = activity.purpose,
    targetDescription = activity.targetDescription,
)

private fun activityStatusText(status: String): String = when (status.lowercase()) {
    "running", "proposed" -> "Working"
    "completed" -> "Done"
    "confirmation" -> "Needs confirmation"
    "attention" -> "Needs attention"
    "failed" -> "Failed"
    else -> "Info"
}

private fun statusGlyph(status: String): String = when (status.lowercase()) {
    "completed" -> "✓"
    "failed", "attention" -> "!"
    "confirmation" -> "?"
    else -> "•"
}

@Composable
private fun activityStatusColor(status: String): Color = when (status.lowercase()) {
    "failed", "attention" -> MaterialTheme.colorScheme.error
    "confirmation" -> MaterialTheme.colorScheme.tertiary
    "completed" -> Color(0xFF16805A)
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun RequestComposer(enabled: Boolean, onSend: (String) -> Unit) {
    var request by rememberSaveable { mutableStateOf("") }
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = request,
                onValueChange = { request = it },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (enabled) "Message DHD" else "DHD is working…") },
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                ),
            )
            Button(
                onClick = {
                    val text = request.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        request = ""
                    }
                },
                enabled = enabled && request.isNotBlank(),
                modifier = Modifier.height(52.dp),
            ) { Text("Send") }
        }
    }
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
    var enabledPackages by remember { mutableStateOf(permissions.enabledPackages()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("Shizuku", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(shizukuStatus.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "DHD uses Shizuku to operate the physical display through typed actions. It never receives a shell or unrestricted app access.",
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
                    "Every app starts disabled. Enable only the apps DHD may operate; there is no global enable-all switch.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
            }
            if (apps.isEmpty()) {
                item { Text("No launchable user apps were found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(apps, key = { it.packageName }) { app ->
                    val enabled = app.packageName in enabledPackages
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, fontWeight = FontWeight.Medium)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun SessionState.isActive(): Boolean = this is SessionState.Running || this is SessionState.Paused

private fun SessionState.sessionIdOrNullForUi(): String? = when (this) {
    SessionState.Idle -> null
    is SessionState.Running -> sessionId
    is SessionState.Paused -> sessionId
    is SessionState.Stopped -> sessionId
    is SessionState.Completed -> sessionId
}

private fun TimelineItem.belongsTo(runId: String?): Boolean = when (this) {
    is TimelineItem.Message -> this.runId == runId
    is TimelineItem.Activity -> this.runId == runId
}

private const val RECENT_HISTORY_WINDOW_MS = 24L * 60L * 60L * 1000L
