@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID
import com.phonecontrol.assistant.data.PHONE_ASSISTANT_EXECUTE_TOOL
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
    onSteerRequest: (String) -> Boolean,
    onOpenSettings: () -> Unit,
    onStartFresh: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val state by coordinator.state.collectAsState()
    val timeline by store.timeline(DHD_CONVERSATION_ID).collectAsState()
    val active = state.isActive()
    val canSteer = state is SessionState.Running
    var showStartFreshConfirmation by rememberSaveable { mutableStateOf(false) }
    var steerDraft by rememberSaveable { mutableStateOf("") }
    var steerDraftSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeSessionId = state.sessionIdOrNullForUi()
    LaunchedEffect(activeSessionId) {
        // A draft belongs to the run in which it was composed. Do not carry an
        // unsent steer into a newly started task or a rotated session.
        if (steerDraftSessionId != activeSessionId) {
            steerDraft = ""
            steerDraftSessionId = activeSessionId
        }
    }
    val recentCutoff = System.currentTimeMillis() - RECENT_HISTORY_WINDOW_MS
    val recentTimeline = timeline.filter { item ->
        item.timestampEpochMs >= recentCutoff ||
            (active && item.belongsTo(state.sessionIdOrNullForUi()))
    }

    Scaffold(
        containerColor = colors.background,
        contentColor = colors.textPrimary,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                    actionIconContentColor = colors.textPrimary,
                ),
                title = {
                    Text(
                        text = "DHD",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
                actions = {
                    // Start fresh circular button (48dp)
                    Surface(
                        shape = CircleShape,
                        color = colors.composerBackground,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !active) { showStartFreshConfirmation = true },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_compose_new),
                                contentDescription = "Start fresh",
                                tint = if (active) colors.textSecondary.copy(alpha = 0.4f) else colors.textPrimary,
                                modifier = Modifier.size(23.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    // Settings circular button (48dp)
                    Surface(
                        shape = CircleShape,
                        color = colors.composerBackground,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { onOpenSettings() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = "Settings",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(23.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .background(colors.background),
        ) {
            // Chat timeline content with generous bottom padding so it can scroll fully above composer
            if (recentTimeline.isEmpty()) {
                EmptyChat(
                    modifier = Modifier.fillMaxSize(),
                    onSelectPrompt = { prompt -> onRunRequest(prompt, DHD_CONVERSATION_ID) },
                )
            } else {
                ConversationTimeline(
                    timeline = recentTimeline,
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 12.dp,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 110.dp,
                    ),
                )
            }

            // Floating composer with zero rectangular background cutout
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 14.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (canSteer && steerDraft.isNotBlank()) {
                        SteerDraftBar(
                            text = steerDraft,
                            onSteer = {
                                if (onSteerRequest(steerDraft)) {
                                    steerDraft = ""
                                    true
                                } else {
                                    false
                                }
                            },
                            onDismiss = { steerDraft = "" },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    RequestComposer(
                        enabled = !active || canSteer,
                        isActive = active,
                        canSteer = canSteer,
                        onSend = { request ->
                            if (canSteer) {
                                // Sending from the composer creates a draft;
                                // the explicit Steer button above performs the
                                // actual turn/steer request.
                                steerDraft = request
                                true
                            } else {
                                onRunRequest(request, DHD_CONVERSATION_ID)
                                true
                            }
                        },
                        onStop = onStopSession,
                    )
                }
            }
        }
    }

    if (showStartFreshConfirmation) {
        AlertDialog(
            onDismissRequest = { showStartFreshConfirmation = false },
            containerColor = colors.surfaceCard,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Start fresh?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "This clears the DHD conversation timeline and rotates its stored Codex thread " +
                        "binding. App permissions and Shizuku configuration remain unchanged.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStartFreshConfirmation = false
                        onStartFresh()
                    },
                ) {
                    Text("Start fresh", color = colors.accentBlue, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartFreshConfirmation = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun EmptyChat(
    modifier: Modifier = Modifier,
    onSelectPrompt: (String) -> Unit = {},
) {
    val colors = LocalAssistantColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "What can I do on your phone?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                text = "Ask DHD to operate apps on your device. Every purposeful action is shown in real time.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(8.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PromptSuggestionChip(
                    text = "Open Coordinate Benchmark and run 10 rounds",
                    onClick = { onSelectPrompt("Open Coordinate Benchmark and run 10 rounds") },
                )
                PromptSuggestionChip(
                    text = "Check foreground app and describe the screen",
                    onClick = { onSelectPrompt("Check foreground app and describe the screen") },
                )
            }
        }
    }
}

@Composable
private fun PromptSuggestionChip(
    text: String,
    onClick: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private data class TaskGroup(
    val id: String,
    val userMessage: TimelineItem.Message?,
    val steerMessages: List<TimelineItem.Message>,
    val activities: List<TimelineItem.Activity>,
    val assistantMessages: List<TimelineItem.Message>,
    val timestampEpochMs: Long,
)

private class TaskGroupBuilder(val id: String) {
    var userMessage: TimelineItem.Message? = null
    val steerMessages = mutableListOf<TimelineItem.Message>()
    val activities = mutableListOf<TimelineItem.Activity>()
    val assistantMessages = mutableListOf<TimelineItem.Message>()
    var timestampEpochMs: Long = Long.MAX_VALUE

    fun addTimestamp(timestamp: Long) {
        timestampEpochMs = minOf(timestampEpochMs, timestamp)
    }

    fun build(): TaskGroup = TaskGroup(
        id = id,
        userMessage = userMessage,
        steerMessages = steerMessages.toList(),
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
                } else if (item.role == "steer") {
                    builder.steerMessages += item
                } else {
                    builder.assistantMessages += item
                }
            }
            is TimelineItem.Activity -> {
                // The activity feed is an MCP trace, not a general-purpose
                // session log. Keep only physical actions dispatched through
                // phone_assistant_execute and omit legacy confirmation rows.
                if (item.isMcpExecuteActivity()) builder.activities += item
            }
        }
    }
    return builders.values.map(TaskGroupBuilder::build).sortedBy { it.timestampEpochMs }
}

private fun TimelineItem.Activity.isMcpExecuteActivity(): Boolean =
    (toolName == PHONE_ASSISTANT_EXECUTE_TOOL || (toolName == null && actionType != null)) &&
        !status.equals("confirmation", ignoreCase = true)

@Composable
private fun ConversationTimeline(
    timeline: List<TimelineItem>,
    state: SessionState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 12.dp),
) {
    val listState = rememberLazyListState()
    val groups = remember(timeline) { groupTimeline(timeline) }
    LaunchedEffect(
        groups.lastOrNull()?.id,
        groups.lastOrNull()?.activities?.size,
        groups.lastOrNull()?.steerMessages?.size,
    ) {
        if (groups.isNotEmpty()) listState.animateScrollToItem(groups.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(groups, key = { it.id }) { group ->
            TaskGroupCard(
                group = group,
                active = state.isActive() && state.sessionIdOrNullForUi() == group.id,
            )
        }
    }
}

@Composable
private fun TaskGroupCard(
    group: TaskGroup,
    active: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // User message bubble (ChatGPT navy bubble in dark, soft gray bubble in light)
        group.userMessage?.let { MessageBubble(it) }

        // Steering instructions stay attached to the current run instead of
        // appearing as a new task.
        group.steerMessages.forEach { SteerMessageBubble(it) }

        // Shimmering thinking loading indicator (visible when actively planning/thinking)
        if (active) {
            ShimmerThinkingIndicator()
        }

        // Assistant response message(s)
        group.assistantMessages.forEach { MessageBubble(it) }

        // Action activities feed
        if (group.activities.isNotEmpty()) {
            ActivityFeed(
                activities = group.activities,
                active = active,
            )
        }
    }
}

@Composable
private fun ShimmerThinkingIndicator() {
    val colors = LocalAssistantColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_shimmer")

    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = -150f,
        targetValue = 450f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_translate",
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            colors.accentBlue.copy(alpha = 0.35f),
            colors.accentBlue,
            colors.textPrimary,
            colors.accentBlue,
            colors.accentBlue.copy(alpha = 0.35f),
        ),
        start = Offset(shimmerTranslate, 0f),
        end = Offset(shimmerTranslate + 160f, 0f),
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_bot),
            contentDescription = "Thinking",
            tint = colors.accentBlue.copy(alpha = pulseAlpha),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "Thinking…",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(brush = shimmerBrush),
        )
    }
}

@Composable
private fun MessageBubble(message: TimelineItem.Message) {
    val colors = LocalAssistantColors.current
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Surface(
                color = colors.userBubble,
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 6.dp,
                ),
                modifier = Modifier.padding(start = 48.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = message.text,
                        color = colors.userBubbleText,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 24.dp, top = 2.dp),
            ) {
                Text(
                    text = message.text,
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            }
        }
    }
}

@Composable
private fun SteerDraftBar(
    text: String,
    onSteer: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Surface(
        color = colors.surfaceCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "↪",
                color = colors.textSecondary,
                fontSize = 18.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = text,
                color = colors.textPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "↪ Steer",
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSteer() }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Discard steer draft",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable { onDismiss() }
                    .padding(3.dp),
            )
            Text(
                text = "⋯",
                color = colors.textSecondary,
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
    }
}

@Composable
private fun SteerMessageBubble(message: TimelineItem.Message) {
    val colors = LocalAssistantColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = colors.surfaceCard,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colors.accentBlue.copy(alpha = 0.65f)),
            modifier = Modifier.padding(start = 64.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = "Steer",
                    color = colors.accentBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = message.text,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ActivityFeed(
    activities: List<TimelineItem.Activity>,
    active: Boolean,
) {
    val colors = LocalAssistantColors.current
    var expanded by rememberSaveable { mutableStateOf(true) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_connected_nodes),
                    contentDescription = PHONE_ASSISTANT_EXECUTE_TOOL,
                    tint = if (active) colors.accentBlue else colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$PHONE_ASSISTANT_EXECUTE_TOOL · ${activities.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
                    contentDescription = "Toggle action steps",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(15.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    HorizontalDivider(color = colors.borderColor)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(activities, key = { it.id }) { activity ->
                            ActionStepRow(activity)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionStepRow(activity: TimelineItem.Activity) {
    val colors = LocalAssistantColors.current
    var detailsExpanded by rememberSaveable(activity.id) { mutableStateOf(false) }
    val statusColor = when (activity.status.lowercase()) {
        "completed" -> colors.accentGreen
        "failed" -> colors.errorRed
        "attention" -> colors.warningAmber
        else -> colors.accentBlue
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { detailsExpanded = !detailsExpanded }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_connected_nodes),
                contentDescription = PHONE_ASSISTANT_EXECUTE_TOOL,
                tint = statusColor,
                modifier = Modifier.size(18.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activityLabel(activity),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = if (detailsExpanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!detailsExpanded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text(
                            text = "$PHONE_ASSISTANT_EXECUTE_TOOL · ${activityStatusText(activity.status)}",
                            color = statusColor,
                            fontSize = 11.sp,
                        )
                        Text(
                            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(activity.timestampEpochMs)),
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        if (detailsExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 25.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                activity.targetDescription?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = "Target: $it",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }
                activity.message.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = "Detail: $it",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }
                Text(
                    text = "Time: ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(activity.timestampEpochMs))}",
                    fontSize = 11.sp,
                    color = colors.textSecondary.copy(alpha = 0.8f),
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
    "running", "proposed" -> "Executing"
    "completed" -> "Success"
    "attention" -> "Needs attention"
    "failed" -> "Failed"
    else -> "Info"
}

@Composable
private fun RequestComposer(
    enabled: Boolean,
    isActive: Boolean,
    canSteer: Boolean,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isImeVisible = imeInsets.getBottom(density) > 0

    // Using TextFieldValue for accurate cursor position & selection tracking with Wispr Flow / Accessibility / IME
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var isFocused by remember { mutableStateOf(false) }
    var isImeHiding by remember { mutableStateOf(false) }

    LaunchedEffect(density, imeInsets) {
        var previousImeBottom = imeInsets.getBottom(density)
        snapshotFlow { imeInsets.getBottom(density) }
            .collect { currentImeBottom ->
                isImeHiding = previousImeBottom > 0 && currentImeBottom < previousImeBottom
                previousImeBottom = currentImeBottom
            }
    }

    // When the on-screen keyboard is dismissed, automatically clear focus and collapse the composer back
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible && textFieldValue.text.isBlank()) {
            focusManager.clearFocus()
            isFocused = false
        }
    }

    val hasText = textFieldValue.text.isNotBlank()
    val isExpanded = !isImeHiding && (isImeVisible || (isFocused && hasText))

    Surface(
        color = colors.composerBackground,
        shape = RoundedCornerShape(if (isExpanded) 22.dp else 30.dp),
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
    ) {
        Column(
            modifier = Modifier
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
                .padding(
                    start = 18.dp,
                    end = 10.dp,
                    top = if (isExpanded) 12.dp else 7.dp,
                    bottom = if (isExpanded) 10.dp else 7.dp,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    enabled = enabled,
                    textStyle = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    ),
                    cursorBrush = SolidColor(colors.accentBlue),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                        .semantics {
                            contentDescription = "Ask DHD input"
                        }
                        .onFocusChanged { isFocused = it.isFocused },
                    minLines = if (isExpanded) 2 else 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val text = textFieldValue.text.trim()
                            if (text.isNotEmpty() && enabled) {
                                if (onSend(text)) {
                                    textFieldValue = TextFieldValue("", selection = TextRange.Zero)
                                    focusManager.clearFocus()
                                }
                            }
                        },
                    ),
                    decorationBox = { innerTextField ->
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = when {
                                    canSteer -> "Do anything"
                                    enabled -> "Ask DHD"
                                    else -> "DHD is working…"
                                },
                                color = colors.textSecondary,
                                fontSize = 16.sp,
                            )
                        }
                        innerTextField()
                    },
                )

                AnimatedVisibility(
                    visible = !isExpanded,
                    enter = fadeIn(animationSpec = tween(120)),
                    exit = fadeOut(animationSpec = tween(50)),
                ) {
                    ActionOrSendButton(
                        isActive = isActive,
                        hasText = hasText,
                        enabled = enabled,
                        colors = colors,
                        onSend = {
                            val text = textFieldValue.text.trim()
                            if (text.isNotEmpty() && onSend(text)) {
                                textFieldValue = TextFieldValue("", selection = TextRange.Zero)
                                focusManager.clearFocus()
                            }
                        },
                        onStop = onStop,
                        canSteer = canSteer,
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(animationSpec = tween(120)),
                exit = fadeOut(animationSpec = tween(50)),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ActionOrSendButton(
                            isActive = isActive,
                            hasText = hasText,
                            enabled = enabled,
                            colors = colors,
                            onSend = {
                                val text = textFieldValue.text.trim()
                                if (text.isNotEmpty() && onSend(text)) {
                                    textFieldValue = TextFieldValue("", selection = TextRange.Zero)
                                    focusManager.clearFocus()
                                }
                            },
                            onStop = onStop,
                            canSteer = canSteer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionOrSendButton(
    isActive: Boolean,
    hasText: Boolean,
    enabled: Boolean,
    canSteer: Boolean,
    colors: AssistantColorScheme,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    if (isActive) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canSteer) {
                Surface(
                    shape = CircleShape,
                    color = if (hasText) colors.sendButtonActiveBg else colors.sendButtonInactiveBg,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(enabled = hasText) { onSend() },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_send_arrow),
                            contentDescription = "Steer active task",
                            tint = if (hasText) colors.sendButtonActiveIcon else colors.sendButtonInactiveIcon,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Active Stop Square Button inside blue circle (Matching Image)
            Surface(
                shape = CircleShape,
                color = colors.accentBlue,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onStop() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_stop),
                        contentDescription = "Stop task",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    } else {
        // Send Upward Arrow Button
        Surface(
            shape = CircleShape,
            color = if (hasText && enabled) colors.sendButtonActiveBg else colors.sendButtonInactiveBg,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(enabled = hasText && enabled) { onSend() },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_send_arrow),
                    contentDescription = "Send",
                    tint = if (hasText && enabled) colors.sendButtonActiveIcon else colors.sendButtonInactiveIcon,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    apps: List<InstalledUserApp>,
    permissions: AppPermissionRepository,
    shizukuStatus: ShizukuStatus,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onOpenApprovedApps: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val enabledCount = remember(permissions.enabledPackages()) { permissions.enabledPackages().size }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                ),
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Surface(
                        shape = CircleShape,
                        color = colors.composerBackground,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Back",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        ) {
            // Preferences Section
            item {
                SettingsSectionHeader("Preferences")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.surfaceCard,
                    border = BorderStroke(1.dp, colors.borderColor),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        // Dark Mode Toggle Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(if (isDarkMode) R.drawable.ic_moon else R.drawable.ic_sun),
                                contentDescription = "Theme",
                                tint = colors.accentBlue,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = "Dark mode",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                )
                                Text(
                                    text = if (isDarkMode) "OLED dark mode" else "Light mode",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                )
                            }
                            Switch(
                                checked = isDarkMode,
                                onCheckedChange = onToggleDarkMode,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = colors.accentBlue,
                                    uncheckedThumbColor = colors.textSecondary,
                                    uncheckedTrackColor = if (colors.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E7EB),
                                    uncheckedBorderColor = colors.borderColor,
                                ),
                            )
                        }

                        HorizontalDivider(color = colors.borderColor)

                        // Approved Apps Row (Navigates to dedicated page)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                                .clickable { onOpenApprovedApps() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_apps),
                                contentDescription = "Approved Apps",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = "Approved Apps",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                )
                                Text(
                                    text = "$enabledCount of ${apps.size} apps enabled",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                )
                            }
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_right),
                                contentDescription = "Open Approved Apps",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Shizuku & Transport Section
            item {
                SettingsSectionHeader("Shizuku & Transport")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.surfaceCard,
                    border = BorderStroke(1.dp, colors.borderColor),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_shield),
                                contentDescription = "Shizuku",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = "Shizuku Service",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                )
                                Text(
                                    text = shizukuStatus.message,
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = if (shizukuStatus.permissionGranted) colors.accentGreen.copy(alpha = 0.2f) else colors.warningAmber.copy(alpha = 0.2f),
                            ) {
                                Text(
                                    text = if (shizukuStatus.permissionGranted) "Active" else "Action needed",
                                    color = if (shizukuStatus.permissionGranted) colors.accentGreen else colors.warningAmber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(start = 36.dp, top = 10.dp),
                        ) {
                            if (shizukuStatus.binderAvailable && !shizukuStatus.permissionGranted) {
                                Button(
                                    onClick = onRequestShizukuPermission,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentBlue),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Request permission", fontSize = 12.sp)
                                }
                            }
                            OutlinedButton(
                                onClick = onRefreshShizuku,
                                border = BorderStroke(1.dp, colors.borderColor),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Refresh", color = colors.textPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // About Section
            item {
                SettingsSectionHeader("About")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.surfaceCard,
                    border = BorderStroke(1.dp, colors.borderColor),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = "Version",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp),
                            )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 14.dp),
                        ) {
                            Text(
                                text = "Version",
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                            )
                            Text(
                                text = "0.1.0 • Android SDK 35",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ApprovedAppsScreen(
    apps: List<InstalledUserApp>,
    permissions: AppPermissionRepository,
    onBack: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    var enabledPackages by remember { mutableStateOf(permissions.enabledPackages()) }
    var isSearchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    BackHandler(enabled = isSearchOpen) {
        isSearchOpen = false
        searchQuery = ""
    }

    LaunchedEffect(isSearchOpen) {
        if (isSearchOpen) {
            focusRequester.requestFocus()
        }
    }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            AnimatedContent(
                targetState = isSearchOpen,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(140))
                },
                label = "search_header_transition",
            ) { searchActive ->
                if (searchActive) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = colors.background,
                        ),
                        title = {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = colors.surfaceCard,
                                border = BorderStroke(1.dp, colors.borderColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_search),
                                        contentDescription = "Search",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        textStyle = TextStyle(
                                            color = colors.textPrimary,
                                            fontSize = 15.sp,
                                        ),
                                        cursorBrush = SolidColor(colors.accentBlue),
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(focusRequester),
                                        singleLine = true,
                                        decorationBox = { innerTextField ->
                                            if (searchQuery.isEmpty()) {
                                                Text(
                                                    text = "Search apps…",
                                                    color = colors.textSecondary,
                                                    fontSize = 15.sp,
                                                )
                                            }
                                            innerTextField()
                                        },
                                    )
                                    if (searchQuery.isNotEmpty()) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.Transparent,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .clickable { searchQuery = "" },
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_close),
                                                    contentDescription = "Clear search",
                                                    tint = colors.textSecondary,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    isSearchOpen = false
                                    searchQuery = ""
                                },
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                Text(
                                    text = "Cancel",
                                    color = colors.accentBlue,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = colors.background,
                            titleContentColor = colors.textPrimary,
                            actionIconContentColor = colors.textPrimary,
                        ),
                        title = { Text("Approved Apps", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            Surface(
                                shape = CircleShape,
                                color = colors.composerBackground,
                                border = BorderStroke(1.dp, colors.borderColor),
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable { onBack() },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = "Back",
                                        tint = colors.textPrimary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        },
                        actions = {
                            Surface(
                                shape = CircleShape,
                                color = colors.composerBackground,
                                border = BorderStroke(1.dp, colors.borderColor),
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable { isSearchOpen = true },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_search),
                                        contentDescription = "Search",
                                        tint = colors.textPrimary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        ) {
            if (filteredApps.isEmpty()) {
                item {
                    Text(
                        text = if (searchQuery.isBlank()) "No launchable user apps found." else "No matching apps found.",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                // Continuous joined card container for all apps
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = colors.surfaceCard,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            filteredApps.forEachIndexed { index, app ->
                                val enabled = app.packageName in enabledPackages
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.label,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.textPrimary,
                                            fontSize = 14.sp,
                                        )
                                        Text(
                                            text = app.packageName,
                                            fontSize = 12.sp,
                                            color = colors.textSecondary,
                                        )
                                    }
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = { checked ->
                                            permissions.setEnabled(app.packageName, checked)
                                            enabledPackages = permissions.enabledPackages()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = colors.accentBlue,
                                            uncheckedThumbColor = colors.textSecondary,
                                            uncheckedTrackColor = if (colors.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E7EB),
                                            uncheckedBorderColor = colors.borderColor,
                                        ),
                                    )
                                }
                                if (index < filteredApps.lastIndex) {
                                    HorizontalDivider(color = colors.borderColor)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    val colors = LocalAssistantColors.current
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.textSecondary,
        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
    )
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
