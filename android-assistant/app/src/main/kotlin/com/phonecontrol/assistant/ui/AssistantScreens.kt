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
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.domain.userFacingActivityLabel
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.shizuku.ShizukuStatus
import kotlinx.coroutines.delay
import kotlin.random.Random
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
    shizukuStatus: ShizukuStatus,
    onOpenShizuku: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val state by coordinator.state.collectAsState()
    val timeline by store.timeline(DHD_CONVERSATION_ID).collectAsState()
    val active = state.isActive()
    val canSteer = state is SessionState.Running
    var showStartFreshConfirmation by rememberSaveable { mutableStateOf(false) }
    var steerDraft by rememberSaveable { mutableStateOf("") }
    var steerDraftSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var composerEditText by rememberSaveable { mutableStateOf<String?>(null) }
    val activeSessionId = state.sessionIdOrNullForUi()
    LaunchedEffect(activeSessionId) {
        // A draft belongs to the run in which it was composed. Do not carry an
        // unsent steer into a newly started task or a rotated session.
        if (steerDraftSessionId != activeSessionId) {
            steerDraft = ""
            steerDraftSessionId = activeSessionId
        }
    }
    LaunchedEffect(state) {
        val completed = state as? SessionState.Completed ?: return@LaunchedEffect
        val normalRequest = steerDraft.trim()
        if (normalRequest.isBlank() || steerDraftSessionId != completed.sessionId) {
            return@LaunchedEffect
        }

        // A draft that was not explicitly steered becomes the next ordinary
        // request once the current run has reached a successful terminal state.
        steerDraft = ""
        steerDraftSessionId = null
        onRunRequest(normalRequest, DHD_CONVERSATION_ID)
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
                    shizukuStatus = shizukuStatus,
                    onOpenSettings = onOpenSettings,
                    onOpenShizuku = onOpenShizuku,
                    onStopSession = onStopSession,
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
                            onEdit = {
                                composerEditText = steerDraft
                                steerDraft = ""
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    RequestComposer(
                        enabled = !active || canSteer,
                        isActive = active,
                        canSteer = canSteer,
                        editText = composerEditText,
                        onEditTextConsumed = { composerEditText = null },
                        onSend = { request ->
                            if (canSteer) {
                                // Sending from the composer creates a draft;
                                // the explicit Steer button above performs the
                                // actual turn/steer request.
                                steerDraft = request
                                steerDraftSessionId = activeSessionId
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
                // The activity feed is a DHD tool trace, not a general-purpose
                // session log. Keep only physical action events and omit
                // legacy confirmation rows.
                if (item.isDhdActionActivity()) builder.activities += item
            }
        }
    }
    return builders.values.map(TaskGroupBuilder::build).sortedBy { it.timestampEpochMs }
}

private fun TimelineItem.Activity.isDhdActionActivity(): Boolean =
    !status.equals("confirmation", ignoreCase = true)

@Composable
private fun ConversationTimeline(
    timeline: List<TimelineItem>,
    state: SessionState,
    shizukuStatus: ShizukuStatus,
    onOpenSettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onStopSession: () -> Unit,
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
                state = state,
                shizukuStatus = shizukuStatus,
                onOpenSettings = onOpenSettings,
                onOpenShizuku = onOpenShizuku,
                onStopSession = onStopSession,
                active = state.isActive() && state.sessionIdOrNullForUi() == group.id,
            )
        }
    }
}

@Composable
private fun TaskGroupCard(
    group: TaskGroup,
    state: SessionState,
    shizukuStatus: ShizukuStatus,
    onOpenSettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onStopSession: () -> Unit,
    active: Boolean,
) {
    var traceExpanded by rememberSaveable(group.id) { mutableStateOf(false) }

    val durationSeconds = remember(group) {
        val start = group.userMessage?.timestampEpochMs ?: group.timestampEpochMs
        val end = group.assistantMessages.lastOrNull()?.timestampEpochMs
            ?: group.activities.lastOrNull()?.timestampEpochMs
            ?: start
        maxOf(1L, (end - start) / 1000L)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // User message bubble (ChatGPT navy bubble in dark, soft gray bubble in light)
        group.userMessage?.let { MessageBubble(it) }

        // Steering instructions stay attached to the current run instead of
        // appearing as a new task.
        group.steerMessages.forEach { SteerMessageBubble(it) }

        // Keep the playful status for healthy work, but replace it with a
        // concrete recovery card whenever the phone cannot make progress.
        if (active) {
            when (state) {
                is SessionState.Running -> RunningStatusIndicator(
                    currentPurpose = state.currentPurpose,
                    startedAtEpochMs = state.startedAtEpochMs,
                    shizukuStatus = shizukuStatus,
                    onOpenSettings = onOpenSettings,
                    onOpenShizuku = onOpenShizuku,
                    onStopSession = onStopSession,
                )
                is SessionState.Paused -> PausedStatusIndicator(
                    currentPurpose = state.currentPurpose,
                )
                else -> Unit
            }
        }

        // While active: show in-flight tool steps directly under thinking indicator (clean, no boxed container)
        if (active && group.activities.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                group.activities.forEach { activity ->
                    TraceStepRow(activity)
                }
            }
        }

        // When completed (!active): everything collapses under "Worked for Xs >"
        if (!active && (group.assistantMessages.isNotEmpty() || group.activities.isNotEmpty())) {
            WorkedTraceSection(
                durationSeconds = durationSeconds,
                activities = group.activities,
                expanded = traceExpanded,
                onToggleExpand = { traceExpanded = !traceExpanded },
            )
        }

        // Assistant response message(s)
        group.assistantMessages.forEach { MessageBubble(it) }
    }
}

@Composable
private fun RunningStatusIndicator(
    currentPurpose: String,
    startedAtEpochMs: Long,
    shizukuStatus: ShizukuStatus,
    onOpenSettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onStopSession: () -> Unit,
) {
    val elapsedSeconds = rememberElapsedSeconds(startedAtEpochMs)
    val shizukuCheckFinished = !shizukuStatus.message.startsWith("Checking", ignoreCase = true)
    if (shizukuCheckFinished && !shizukuStatus.privilegedApiReady) {
        ShizukuRecoveryCard(
            status = shizukuStatus,
            onOpenSettings = onOpenSettings,
            onOpenShizuku = onOpenShizuku,
        )
        return
    }

    val waitingForCompanion = currentPurpose.equals("Waiting for desktop Codex bridge", ignoreCase = true) ||
        (currentPurpose.equals("Preparing request", ignoreCase = true) && elapsedSeconds >= COMPANION_WAIT_CALLOUT_SECONDS)
    if (waitingForCompanion) {
        CompanionRecoveryCard(
            elapsedSeconds = elapsedSeconds,
            onStopSession = onStopSession,
        )
        return
    }

    if (currentPurpose.equals("Needs your attention", ignoreCase = true)) {
        AttentionRecoveryCard(onStopSession = onStopSession)
        return
    }

    ShimmerThinkingIndicator(
        currentPurpose = currentPurpose,
        startedAtEpochMs = startedAtEpochMs,
        elapsedSeconds = elapsedSeconds,
    )
}

@Composable
private fun ShimmerThinkingIndicator(
    currentPurpose: String,
    startedAtEpochMs: Long,
    elapsedSeconds: Long,
) {
    val colors = LocalAssistantColors.current
    var wordIndex by rememberSaveable(startedAtEpochMs) {
        mutableStateOf(Random.nextInt(THINKING_WORDS.size))
    }
    LaunchedEffect(startedAtEpochMs) {
        while (true) {
            delay(2_400L)
            wordIndex = nextThinkingWordIndex(wordIndex)
        }
    }
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

    Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_bot),
                contentDescription = "Thinking",
                tint = colors.accentBlue.copy(alpha = pulseAlpha),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = THINKING_WORDS[wordIndex],
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                style = TextStyle(brush = shimmerBrush),
            )
        }
        Text(
            text = "${thinkingDetail(currentPurpose, elapsedSeconds)} · ${elapsedSeconds}s",
            fontSize = 12.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 23.dp, top = 2.dp),
        )
    }
}

@Composable
private fun PausedStatusIndicator(currentPurpose: String) {
    val colors = LocalAssistantColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_stop),
            contentDescription = "Paused",
            tint = colors.warningAmber,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Column {
            Text(
                text = "Paused",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.warningAmber,
            )
            Text(
                text = thinkingDetail(currentPurpose, 0L),
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun CompanionRecoveryCard(
    elapsedSeconds: Long,
    onStopSession: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    RecoveryCard(
        icon = R.drawable.ic_laptop,
        title = "Desktop companion not connected",
        detail = "DHD has not sent any phone action yet. Check the Codex companion and try again.",
        accent = colors.warningAmber,
        actionLabel = "Stop waiting",
        onAction = onStopSession,
        trailing = "Waiting ${elapsedSeconds}s",
    )
}

@Composable
private fun ShizukuRecoveryCard(
    status: ShizukuStatus,
    onOpenSettings: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    RecoveryCard(
        icon = R.drawable.ic_shield,
        title = if (status.binderAvailable) "Shizuku permission needed" else "Shizuku isn’t running",
        detail = "DHD is paused before the next phone action. ${status.message}",
        accent = colors.warningAmber,
        actionLabel = "Open Shizuku",
        onAction = onOpenShizuku,
        secondaryActionLabel = "DHD settings",
        onSecondaryAction = onOpenSettings,
    )
}

@Composable
private fun AttentionRecoveryCard(onStopSession: () -> Unit) {
    val colors = LocalAssistantColors.current
    RecoveryCard(
        icon = R.drawable.ic_info,
        title = "DHD needs your attention",
        detail = "The phone screen changed unexpectedly. Review the phone before continuing.",
        accent = colors.warningAmber,
        actionLabel = "Stop",
        onAction = onStopSession,
    )
}

@Composable
private fun RecoveryCard(
    icon: Int,
    title: String,
    detail: String,
    accent: Color,
    actionLabel: String,
    onAction: () -> Unit,
    trailing: String? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                trailing?.let {
                    Text(text = it, color = colors.textSecondary, fontSize = 11.sp)
                }
            }
            Text(
                text = detail,
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(start = 27.dp, top = 5.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 27.dp, top = 9.dp),
            ) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(actionLabel, fontSize = 12.sp)
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(
                        onClick = onSecondaryAction,
                        border = BorderStroke(1.dp, colors.borderColor),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(secondaryActionLabel, color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberElapsedSeconds(startedAtEpochMs: Long): Long {
    var elapsedSeconds by remember(startedAtEpochMs) {
        mutableStateOf(((System.currentTimeMillis() - startedAtEpochMs) / 1_000L).coerceAtLeast(0L))
    }
    LaunchedEffect(startedAtEpochMs) {
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - startedAtEpochMs) / 1_000L).coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    return elapsedSeconds
}

private fun thinkingDetail(currentPurpose: String, elapsedSeconds: Long): String = when {
    currentPurpose.equals("Preparing request", ignoreCase = true) && elapsedSeconds >= COMPANION_WAIT_CALLOUT_SECONDS ->
        "Waiting for the desktop companion"
    currentPurpose.equals("Preparing request", ignoreCase = true) -> "Connecting to the desktop companion"
    currentPurpose.equals("Codex is planning", ignoreCase = true) -> "Thinking…"
    else -> currentPurpose.ifBlank { "Preparing the next step" }
}

private val THINKING_WORDS = listOf(
    "Thinking…",
    "DHD-ing…",
    "Discombobulating…",
    "NPC-ing…",
    "Combobulating…",
    "Recombobulating…",
    "Cerebrating…",
    "Noodling…",
    "Orchestrating…",
    "Razzle-dazzling…",
    "Vibing…",
    "Accomplishing…",
    "Actioning…",
    "Actualizing…",
    "Architecting…",
    "Baking…",
    "Beaming…",
    "Beboppin’…",
    "Bloviating…",
    "Boogieing…",
    "Boondoggling…",
    "Booping…",
    "Bootstrapping…",
    "Brewing…",
    "Burrowing…",
    "Calculating…",
    "Canoodling…",
    "Caramelizing…",
    "Cascading…",
    "Catapulting…",
    "Channeling…",
    "Choreographing…",
    "Churning…",
    "Coalescing…",
    "Composing…",
    "Computing…",
    "Concocting…",
    "Considering…",
    "Contemplating…",
    "Cooking…",
    "Crafting…",
    "Creating…",
    "Crunching…",
    "Crystallizing…",
    "Cultivating…",
    "Deciphering…",
    "Deliberating…",
    "Determining…",
    "Dilly-dallying…",
    "Doing…",
    "Doodling…",
    "Drizzling…",
    "Effecting…",
    "Elucidating…",
    "Embellishing…",
    "Enchanting…",
    "Envisioning…",
    "Fermenting…",
    "Fiddle-faddling…",
    "Finagling…",
    "Flambeing…",
    "Flibbertigibbeting…",
    "Flowing…",
    "Fluttering…",
    "Forging…",
    "Forming…",
    "Frolicking…",
    "Gallivanting…",
    "Garnishing…",
    "Generating…",
    "Germinating…",
    "Gitifying…",
    "Grooving…",
    "Hatching…",
    "Herding…",
    "Honking…",
    "Hullaballooing…",
    "Hyperspacing…",
    "Ideating…",
    "Imagining…",
    "Improvising…",
    "Incubating…",
    "Inferring…",
    "Infusing…",
    "Jitterbugging…",
    "Kneading…",
    "Leavening…",
    "Levitating…",
    "Lollygagging…",
    "Manifesting…",
    "Marinating…",
    "Meandering…",
    "Metamorphosing…",
    "Moseying…",
    "Mulling…",
    "Musing…",
    "Nesting…",
    "Orbiting…",
    "Percolating…",
    "Perusing…",
    "Philosophising…",
    "Pondering…",
    "Pontificating…",
    "Pouncing…",
    "Processing…",
    "Proofing…",
    "Puttering…",
    "Puzzling…",
    "Quantumizing…",
    "Reticulating…",
    "Roosting…",
    "Ruminating…",
    "Sauteing…",
    "Scampering…",
    "Schlepping…",
    "Scurrying…",
    "Seasoning…",
    "Shenaniganing…",
    "Shimmying…",
    "Simmering…",
    "Skedaddling…",
    "Sketching…",
    "Slithering…",
    "Smooshing…",
    "Sock-hopping…",
    "Spelunking…",
    "Spinning…",
    "Sprouting…",
    "Stewing…",
    "Synthesizing…",
    "Tempering…",
    "Thundering…",
    "Tinkering…",
    "Tomfoolering…",
    "Transfiguring…",
    "Transmuting…",
    "Unfurling…",
    "Unravelling…",
    "Warping…",
    "Whatchamacalliting…",
    "Whirring…",
    "Whisking…",
    "Wibbling…",
    "Working…",
    "Wrangling…",
    "Zesting…",
    "Zigzagging…",
    "Phone-wrangling…",
    "Tap-dancing…",
    "Screen-sleuthing…",
    "Guard-checking…",
)

private fun nextThinkingWordIndex(previous: Int): Int {
    if (THINKING_WORDS.size < 2) return 0
    var next: Int
    do {
        next = Random.nextInt(THINKING_WORDS.size)
    } while (next == previous)
    return next
}

private const val COMPANION_WAIT_CALLOUT_SECONDS = 15L

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
                        text = parseInlineMarkdown(message.text, colors),
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
                MarkdownContent(
                    markdown = message.text,
                    baseTextStyle = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = colors.textPrimary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun WorkedTraceSection(
    durationSeconds: Long,
    activities: List<TimelineItem.Activity>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onToggleExpand() }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Worked for ${durationSeconds}s",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
            )
            Icon(
                painter = painterResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                contentDescription = if (expanded) "Collapse trace" else "Expand trace",
                tint = colors.textSecondary,
                modifier = Modifier.size(13.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(180)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (activities.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 3.dp, horizontal = 2.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_connected_nodes),
                            contentDescription = "Finished",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Completed in ${durationSeconds}s · Direct response",
                            fontSize = 13.5.sp,
                            color = colors.textSecondary,
                        )
                    }
                } else {
                    activities.forEach { activity ->
                        TraceStepRow(activity)
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceStepRow(activity: TimelineItem.Activity) {
    val colors = LocalAssistantColors.current

    val statusColor = when (activity.status.lowercase()) {
        "completed" -> colors.accentGreen
        "failed" -> colors.errorRed
        "attention" -> colors.warningAmber
        else -> colors.textSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 3.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_connected_nodes),
            contentDescription = "Tool Call",
            tint = statusColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = activityLabel(activity),
            fontSize = 13.5.sp,
            color = colors.textPrimary,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SteerDraftBar(
    text: String,
    onSteer: () -> Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
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
                    .padding(start = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSteer() }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Discard steer draft",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onDismiss() }
                    .padding(8.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_compose_new),
                contentDescription = "Edit steer draft",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onEdit() }
                    .padding(7.dp),
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



private fun activityLabel(activity: TimelineItem.Activity): String = userFacingActivityLabel(
    actionType = activity.actionType?.let { runCatching { com.phonecontrol.assistant.domain.ActionType.valueOf(it) }.getOrNull() },
    purpose = activity.purpose,
    targetDescription = activity.targetDescription,
)



@Composable
private fun RequestComposer(
    enabled: Boolean,
    isActive: Boolean,
    canSteer: Boolean,
    editText: String? = null,
    onEditTextConsumed: () -> Unit = {},
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
    val composerFocusRequester = remember { FocusRequester() }

    LaunchedEffect(editText) {
        if (!editText.isNullOrBlank()) {
            textFieldValue = TextFieldValue(
                editText,
                selection = TextRange(editText.length),
            )
            composerFocusRequester.requestFocus()
            onEditTextConsumed()
        }
    }

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
                        .focusRequester(composerFocusRequester)
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
                        .clickable(enabled = hasText && enabled) { onSend() },
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
