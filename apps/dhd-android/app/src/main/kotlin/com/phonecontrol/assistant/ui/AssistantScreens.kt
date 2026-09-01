@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import com.phonecontrol.assistant.bridge.DevBridgeServer
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.domain.userFacingActivityLabel
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.shizuku.ShizukuStatus
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun AssistantScreen(
    store: ConversationStore,
    coordinator: SessionCoordinator,
    @Suppress("UNUSED_PARAMETER") initialConversationId: String?,
    onRunRequest: (String, String?, String?) -> Unit,
    reasoningEffort: ReasoningEffort,
    visibleReasoningEfforts: List<ReasoningEffort>,
    onSelectReasoningEffort: (ReasoningEffort) -> Unit,
    onStopSession: () -> Unit,
    onSteerRequest: (String) -> Boolean,
    onOpenSettings: () -> Unit,
    onStartFresh: () -> Unit,
    shizukuStatus: ShizukuStatus,
    companionConnected: Boolean,
    onOpenShizuku: () -> Unit,
    onOpenCompanion: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val state by coordinator.state.collectAsState()
    val timeline by store.timeline(DHD_CONVERSATION_ID).collectAsState()
    val active = state.isActive()
    val canSteer = state is SessionState.Running
    var showStartFreshConfirmation by rememberSaveable { mutableStateOf(false) }
    var steerDraft by rememberSaveable { mutableStateOf("") }
    var steerDraftSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var steerDraftReasoningEffort by rememberSaveable { mutableStateOf<String?>(null) }
    var composerEditText by rememberSaveable { mutableStateOf<String?>(null) }
    var showReasoningSelector by rememberSaveable { mutableStateOf(false) }
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
        onRunRequest(
            normalRequest,
            DHD_CONVERSATION_ID,
            steerDraftReasoningEffort ?: reasoningEffort.codexValue,
        )
        steerDraftReasoningEffort = null
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
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
                    .background(colors.background)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                // Chat timeline stays strictly above composer area with soft fade at the bottom
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fadePx = 24.dp.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color.Black,
                                    (1f - (fadePx / size.height).coerceIn(0f, 1f)) to Color.Black,
                                    1.0f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    if (recentTimeline.isEmpty()) {
                        EmptyChat(
                            modifier = Modifier.fillMaxSize(),
                            onSelectPrompt = {
                                prompt -> onRunRequest(prompt, DHD_CONVERSATION_ID, reasoningEffort.codexValue)
                            },
                        )
                    } else {
                        ConversationTimeline(
                            timeline = recentTimeline,
                            state = state,
                            shizukuStatus = shizukuStatus,
                            companionConnected = companionConnected,
                            onOpenSettings = onOpenSettings,
                            onOpenShizuku = onOpenShizuku,
                            onOpenCompanion = onOpenCompanion,
                            onStopSession = onStopSession,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 12.dp,
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 44.dp,
                            ),
                        )
                    }
                }

                // Bottom composer bar with solid background - nothing scrolls behind or between composer and keyboard
                Surface(
                    color = colors.background,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = if (showReasoningSelector) 0f else 1f
                        },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (canSteer && steerDraft.isNotBlank()) {
                            SteerDraftBar(
                                text = steerDraft,
                                onSteer = {
                                    if (onSteerRequest(steerDraft)) {
                                        steerDraft = ""
                                        steerDraftReasoningEffort = null
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onDismiss = {
                                    steerDraft = ""
                                    steerDraftReasoningEffort = null
                                },
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
                            reasoningEffort = reasoningEffort,
                            visibleReasoningEfforts = visibleReasoningEfforts,
                            showReasoningSelector = showReasoningSelector,
                            onOpenReasoningSelector = { showReasoningSelector = true },
                            onExpandedChanged = { expanded ->
                                if (!expanded) showReasoningSelector = false
                            },
                            editText = composerEditText,
                            onEditTextConsumed = { composerEditText = null },
                            onSend = { request ->
                                if (canSteer) {
                                    steerDraft = request
                                    steerDraftSessionId = activeSessionId
                                    steerDraftReasoningEffort = reasoningEffort.codexValue
                                    true
                                } else {
                                    onRunRequest(request, DHD_CONVERSATION_ID, reasoningEffort.codexValue)
                                    true
                                }
                            },
                            onStop = onStopSession,
                        )
                    }
                }
            }

            if (showReasoningSelector) {
                ReasoningEffortOverlay(
                    selectedEffort = reasoningEffort,
                    visibleEfforts = visibleReasoningEfforts,
                    onSelect = onSelectReasoningEffort,
                    onDismiss = { showReasoningSelector = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                )
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
                text = "Ask DHD to operate apps on your device.",
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
                    text = "Call my Mom",
                    onClick = { onSelectPrompt("Call my Mom") },
                )
                PromptSuggestionChip(
                    text = "Play me \"Jesus be the name\" on Spotify",
                    onClick = { onSelectPrompt("Play me \"Jesus be the name\" on Spotify") },
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
    companionConnected: Boolean,
    onOpenSettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenCompanion: () -> Unit,
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
    SelectionContainer {
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
                    companionConnected = companionConnected,
                    onOpenSettings = onOpenSettings,
                    onOpenShizuku = onOpenShizuku,
                    onOpenCompanion = onOpenCompanion,
                    onStopSession = onStopSession,
                    active = state.isActive() && state.sessionIdOrNullForUi() == group.id,
                )
            }
        }
    }
}

@Composable
private fun TaskGroupCard(
    group: TaskGroup,
    state: SessionState,
    shizukuStatus: ShizukuStatus,
    companionConnected: Boolean,
    onOpenSettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenCompanion: () -> Unit,
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
                    companionConnected = companionConnected,
                    onOpenSettings = onOpenSettings,
                    onOpenShizuku = onOpenShizuku,
                    onOpenCompanion = onOpenCompanion,
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

        // Completed runs with phone actions keep a collapsible trace. Direct
        // responses should flow directly from the user message to the answer.
        if (!active && group.activities.isNotEmpty()) {
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
    companionConnected: Boolean,
    onOpenSettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenCompanion: () -> Unit,
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

    val waitingForCompanion = !companionConnected ||
        currentPurpose.equals("Waiting for desktop Codex bridge", ignoreCase = true) ||
        (currentPurpose.equals("Preparing request", ignoreCase = true) && elapsedSeconds >= COMPANION_WAIT_CALLOUT_SECONDS)
    if (waitingForCompanion) {
        CompanionRecoveryCard(
            elapsedSeconds = elapsedSeconds,
            onOpenCompanion = onOpenCompanion,
        )
        return
    }

    if (currentPurpose.equals("Needs your attention", ignoreCase = true)) {
        AttentionRecoveryCard(onStopSession = onStopSession)
        return
    }

    ShimmerThinkingIndicator(
        startedAtEpochMs = startedAtEpochMs,
        elapsedSeconds = elapsedSeconds,
    )
}

@Composable
private fun ShimmerThinkingIndicator(
    startedAtEpochMs: Long,
    elapsedSeconds: Long,
) {
    val colors = LocalAssistantColors.current
    var wordIndex by rememberSaveable(startedAtEpochMs) {
        mutableStateOf(Random.nextInt(THINKING_WORDS.size))
    }
    LaunchedEffect(startedAtEpochMs) {
        while (true) {
            delay(THINKING_WORD_INTERVAL_MS)
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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
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
        Spacer(Modifier.width(6.dp))
        Text(
            text = "·",
            fontSize = 14.sp,
            color = colors.textSecondary.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${elapsedSeconds}s",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Normal,
            color = colors.textSecondary,
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
    onOpenCompanion: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    RecoveryCard(
        icon = R.drawable.ic_laptop,
        title = "Desktop companion not connected",
        detail = "DHD has not sent any phone action yet. Check the Codex companion and try again.",
        accent = colors.warningAmber,
        actionLabel = "Open desktop companion",
        onAction = onOpenCompanion,
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
private const val THINKING_WORD_INTERVAL_MS = 4_000L

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
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onToggleExpand() },
                )
            },
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
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { onToggleExpand() },
                        )
                    }
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                activities.forEach { activity ->
                    TraceStepRow(activity)
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
    reasoningEffort: ReasoningEffort,
    visibleReasoningEfforts: List<ReasoningEffort>,
    showReasoningSelector: Boolean,
    onOpenReasoningSelector: () -> Unit,
    onExpandedChanged: (Boolean) -> Unit,
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
    var staleValueToIgnore by remember { mutableStateOf<String?>(null) }
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

    // Clear the field before handing the request to the session layer. Starting
    // a session can immediately change the composition (and disable the field),
    // while the IME may still deliver one final value-change callback. Clearing
    // first prevents that callback from restoring the submitted text. If the
    // caller rejects the request, restore the draft so it is not lost.
    fun submitRequest() {
        val request = textFieldValue.text.trim()
        if (request.isEmpty() || !enabled) return

        val previousValue = textFieldValue
        staleValueToIgnore = previousValue.text
        textFieldValue = TextFieldValue("", selection = TextRange.Zero)
        focusManager.clearFocus()
        if (!onSend(request)) {
            staleValueToIgnore = null
            textFieldValue = previousValue
        }
    }

    val hasText = textFieldValue.text.isNotBlank()
    val isExpanded = hasText || (!isImeHiding && isImeVisible)
    val isWidened = hasText || (!isImeHiding && isImeVisible)

    LaunchedEffect(isExpanded) {
        onExpandedChanged(isExpanded)
    }

    val horizontalPadding by animateDpAsState(
        targetValue = if (isWidened) 14.dp else 36.dp,
        animationSpec = tween(
            durationMillis = 120,
            easing = FastOutSlowInEasing,
        ),
        label = "composer_horizontal_padding",
    )

    val cornerRadius by animateDpAsState(
        targetValue = if (isExpanded) 22.dp else 28.dp,
        animationSpec = tween(
            durationMillis = 120,
            easing = FastOutSlowInEasing,
        ),
        label = "composer_corner_radius",
    )

    Surface(
        color = colors.composerBackground,
        shape = RoundedCornerShape(cornerRadius),
        border = BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
    ) {
        Column(
            modifier = Modifier
                .padding(
                    start = 10.dp,
                    end = 10.dp,
                    top = 7.dp,
                    bottom = 7.dp,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isExpanded) {
                    AttachButton(colors = colors)
                    Spacer(Modifier.width(10.dp))
                }
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { nextValue ->
                        // Some IMEs deliver the pre-submit value after focus is
                        // cleared. Ignore that one stale callback instead of
                        // putting the just-submitted request back in the field.
                        if (staleValueToIgnore != null && nextValue.text == staleValueToIgnore) {
                            staleValueToIgnore = null
                        } else {
                            staleValueToIgnore = null
                            textFieldValue = nextValue
                        }
                    },
                    enabled = enabled,
                    textStyle = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    ),
                    cursorBrush = SolidColor(colors.accentBlue),
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            start = if (isExpanded) 4.dp else 0.dp,
                            end = if (isExpanded) 4.dp else 10.dp,
                            top = if (isExpanded) 4.dp else 0.dp,
                        )
                        .focusRequester(composerFocusRequester)
                        .semantics {
                            contentDescription = "Ask DHD input"
                        }
                        .onFocusChanged { isFocused = it.isFocused },
                    minLines = 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            submitRequest()
                        },
                    ),
                    decorationBox = { innerTextField ->
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = if (enabled) "Ask DHD" else "DHD is working…",
                                color = colors.textSecondary,
                                fontSize = 16.sp,
                            )
                        }
                        innerTextField()
                    },
                )

                if (!isExpanded) {
                    ActionOrSendButton(
                        isActive = isActive,
                        hasText = hasText,
                        enabled = enabled,
                        colors = colors,
                        onSend = ::submitRequest,
                        onStop = onStop,
                        canSteer = canSteer,
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    animationSpec = tween(120),
                    expandFrom = Alignment.Top,
                ) + fadeIn(animationSpec = tween(80)),
                exit = shrinkVertically(
                    animationSpec = tween(100),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(animationSpec = tween(60)),
            ) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AttachButton(colors = colors)
                        Spacer(Modifier.weight(1f))
                        ReasoningEffortButton(
                            effort = reasoningEffort,
                            visibleEfforts = visibleReasoningEfforts,
                            enabled = enabled && visibleReasoningEfforts.isNotEmpty(),
                            expanded = showReasoningSelector,
                            onClick = onOpenReasoningSelector,
                        )
                        Spacer(Modifier.width(8.dp))
                        ActionOrSendButton(
                            isActive = isActive,
                            hasText = hasText,
                            enabled = enabled,
                            colors = colors,
                            onSend = ::submitRequest,
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
private fun AttachButton(
    colors: AssistantColorScheme,
    onClick: () -> Unit = {},
) {
    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable { onClick() },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_plus),
                contentDescription = "Attach",
                tint = colors.textPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ReasoningEffortButton(
    effort: ReasoningEffort,
    visibleEfforts: List<ReasoningEffort>,
    enabled: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = CircleShape,
        color = if (expanded) colors.sendButtonInactiveBg else Color.Transparent,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = "Reasoning effort: ${effort.label}"
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            ReasoningMeterIcon(
                effort = effort,
                visibleEfforts = visibleEfforts,
                tint = if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.45f),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun ReasoningMeterIcon(
    effort: ReasoningEffort,
    visibleEfforts: List<ReasoningEffort> = ReasoningEffort.entries,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f + size.minDimension * 0.08f)
        val arcRadius = size.minDimension * 0.38f
        val strokeWidth = size.minDimension * 0.1f
        val startAngle = 180f
        val sweepAngle = 180f
        val orderedEfforts = visibleEfforts.distinct().sortedBy(ReasoningEffort::ordinal)
            .ifEmpty { ReasoningEffort.entries }
        val selectedIndex = orderedEfforts.indexOf(effort).coerceAtLeast(0)
        val position = if (orderedEfforts.size == 1) {
            1.0f
        } else {
            selectedIndex.toFloat() / orderedEfforts.lastIndex.toFloat()
        }
        val needleAngle = Math.toRadians((startAngle + sweepAngle * position).toDouble())
        val needleEnd = Offset(
            x = center.x + cos(needleAngle).toFloat() * arcRadius * 0.78f,
            y = center.y + sin(needleAngle).toFloat() * arcRadius * 0.78f,
        )
        val blueSweepAngle = sweepAngle * position

        // Background grey semicircle arc
        drawArc(
            color = tint.copy(alpha = 0.32f),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = androidx.compose.ui.geometry.Size(arcRadius * 2f, arcRadius * 2f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        // Active blue arc
        if (blueSweepAngle > 0f) {
            drawArc(
                color = colors.accentBlue,
                startAngle = startAngle,
                sweepAngle = blueSweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = androidx.compose.ui.geometry.Size(arcRadius * 2f, arcRadius * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        // Needle line
        drawLine(
            color = tint,
            start = center,
            end = needleEnd,
            strokeWidth = strokeWidth * 0.9f,
            cap = StrokeCap.Round,
        )
        drawCircle(color = tint, radius = strokeWidth * 1.05f, center = center)
    }
}

@Composable
private fun ReasoningEffortOverlay(
    selectedEffort: ReasoningEffort,
    visibleEfforts: List<ReasoningEffort>,
    onSelect: (ReasoningEffort) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    val availableEfforts = visibleEfforts
        .distinct()
        .sortedBy(ReasoningEffort::ordinal)
        .ifEmpty { listOf(ReasoningEffort.default) }
    val effectiveSelectedEffort = selectedEffort.takeIf { it in availableEfforts }
        ?: availableEfforts.first()

    BackHandler(enabled = true, onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = effectiveSelectedEffort.label,
                    color = colors.accentBlue,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = " effort",
                    color = colors.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))
            ReasoningEffortTrack(
                selectedEffort = effectiveSelectedEffort,
                visibleEfforts = availableEfforts,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun ReasoningEffortTrack(
    selectedEffort: ReasoningEffort,
    visibleEfforts: List<ReasoningEffort>,
    onSelect: (ReasoningEffort) -> Unit,
) {
    val colors = LocalAssistantColors.current
    val density = LocalDensity.current
    val orderedEfforts = visibleEfforts.distinct().sortedBy(ReasoningEffort::ordinal)
        .ifEmpty { listOf(ReasoningEffort.default) }
    val selectedIndex = orderedEfforts.indexOf(selectedEffort).coerceAtLeast(0)
    val trackShape = CircleShape
    val innerMargin = with(density) { 6.dp.toPx() }
    val thumbRadius = with(density) { 23.dp.toPx() }
    val targetPosition = if (orderedEfforts.size == 1) {
        1.0f
    } else {
        selectedIndex.toFloat() / orderedEfforts.lastIndex.toFloat()
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val animatedPosition by animateFloatAsState(
        targetValue = if (isDragging) dragFraction else targetPosition,
        animationSpec = if (isDragging) spring(stiffness = Spring.StiffnessHigh) else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "reasoning_slider_position",
    )

    val trackBg = if (colors.isDark) colors.composerBackground else Color(0xFFE5E5EA)
    val trackBorder = if (colors.isDark) colors.borderColor else Color(0xFFD1D1D6)

    Surface(
        shape = trackShape,
        color = trackBg,
        border = BorderStroke(1.dp, trackBorder),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clip(trackShape),
        ) {
            val updateFractionAndEffort: (Float, Float) -> Unit = { x, width ->
                if (orderedEfforts.size > 1) {
                    val startX = innerMargin + thumbRadius
                    val endX = width - innerMargin - thumbRadius
                    val usableWidth = (endX - startX).coerceAtLeast(1f)
                    val fraction = ((x - startX) / usableWidth).coerceIn(0f, 1f)
                    dragFraction = fraction
                    val nearestIndex = (fraction * orderedEfforts.lastIndex).roundToInt()
                        .coerceIn(0, orderedEfforts.lastIndex)
                    onSelect(orderedEfforts[nearestIndex])
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription =
                            "Reasoning effort selector. Selected ${selectedEffort.label}. Swipe to choose."
                    }
                    .pointerInput(orderedEfforts) {
                        detectTapGestures(
                            onTap = { offset ->
                                updateFractionAndEffort(offset.x, size.width.toFloat())
                            },
                        )
                    }
                    .pointerInput(orderedEfforts) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                updateFractionAndEffort(offset.x, size.width.toFloat())
                            },
                            onDragEnd = {
                                isDragging = false
                            },
                            onDragCancel = {
                                isDragging = false
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                updateFractionAndEffort(change.position.x, size.width.toFloat())
                            },
                        )
                    },
            ) {
                val startX = innerMargin + thumbRadius
                val endX = size.width - innerMargin - thumbRadius
                val usableWidth = (endX - startX).coerceAtLeast(1f)
                val currentFraction = animatedPosition.coerceIn(0f, 1f)
                val selectedX = startX + usableWidth * currentFraction
                val centerY = size.height / 2f
                val pillHeight = size.height - 2f * innerMargin

                // Active blue pill track
                if (currentFraction > 0.001f) {
                    val pillWidth = (selectedX + thumbRadius - innerMargin).coerceIn(pillHeight, size.width - 2f * innerMargin)
                    drawRoundRect(
                        color = colors.accentBlue,
                        topLeft = Offset(innerMargin, innerMargin),
                        size = androidx.compose.ui.geometry.Size(
                            width = if (currentFraction >= 0.999f) size.width - 2f * innerMargin else pillWidth,
                            height = pillHeight,
                        ),
                        cornerRadius = CornerRadius(pillHeight / 2f, pillHeight / 2f),
                    )
                }

                // Reasoning level dots
                orderedEfforts.forEachIndexed { index, effort ->
                    val dotPosition = if (orderedEfforts.size == 1) {
                        1.0f
                    } else {
                        index.toFloat() / orderedEfforts.lastIndex.toFloat()
                    }
                    val x = startX + usableWidth * dotPosition
                    val dotRadius = with(density) { 4.5.dp.toPx() }
                    if (kotlin.math.abs(x - selectedX) > thumbRadius * 0.65f) {
                        drawCircle(
                            color = if (x < selectedX) {
                                Color.White.copy(alpha = 0.5f)
                            } else {
                                if (colors.isDark) colors.textSecondary.copy(alpha = 0.75f) else Color(0xFF8E8E93)
                            },
                            radius = dotRadius,
                            center = Offset(x, centerY),
                        )
                    }
                }

                // In light mode, add a subtle soft shadow/outline ring around the white knob for crisp definition
                if (!colors.isDark) {
                    drawCircle(
                        color = Color(0x18000000),
                        radius = thumbRadius + with(density) { 1.5.dp.toPx() },
                        center = Offset(selectedX, centerY + with(density) { 0.75.dp.toPx() }),
                    )
                }

                // Solid white circular knob
                drawCircle(
                    color = Color.White,
                    radius = thumbRadius,
                    center = Offset(selectedX, centerY),
                )
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
    bridgeServer: DevBridgeServer,
    themeMode: ThemeMode,
    onSelectThemeMode: (ThemeMode) -> Unit,
    visibleReasoningEfforts: List<ReasoningEffort>,
    onSetReasoningEffortVisibility: (ReasoningEffort, Boolean) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onOpenApprovedApps: () -> Unit,
    onOpenCompanion: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val isFullAccess = remember(permissions.isFullAccessEnabled()) { permissions.isFullAccessEnabled() }
    val enabledCount = remember(permissions.enabledPackages()) { permissions.enabledPackages().size }
    val lanAddresses = remember { bridgeServer.lanIpv4Addresses() }
    var isAppearanceMenuOpen by remember { mutableStateOf(false) }
    var isReasoningMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textPrimary,
                    actionIconContentColor = colors.textPrimary,
                ),
                title = { Text("Settings", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    Surface(
                        shape = CircleShape,
                        color = colors.composerBackground,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Back",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(18.dp),
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
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        ) {
            // Preferences Section
            item {
                SettingsSectionHeader("Preferences")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.settingsCard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        // Appearance Selector Row (with DropdownMenu anchored to right side)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                                .clickable { isAppearanceMenuOpen = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_sun),
                                contentDescription = "Appearance",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = "Appearance",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = themeMode.label,
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                                Icon(
                                    painter = painterResource(if (isAppearanceMenuOpen) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
                                    contentDescription = "Select appearance",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp),
                                )

                                DropdownMenu(
                                    expanded = isAppearanceMenuOpen,
                                    onDismissRequest = { isAppearanceMenuOpen = false },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = if (colors.isDark) Color(0xFF262628) else Color(0xFFFFFFFF),
                                    border = BorderStroke(1.dp, if (colors.isDark) Color(0xFF38383B) else Color(0xFFE5E7EB)),
                                    modifier = Modifier.width(220.dp),
                                ) {
                                    ThemeMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = mode.label,
                                                    color = colors.textPrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = if (themeMode == mode) FontWeight.SemiBold else FontWeight.Normal,
                                                )
                                            },
                                            trailingIcon = {
                                                if (themeMode == mode) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_check),
                                                        contentDescription = "Selected",
                                                        tint = colors.textPrimary,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            },
                                            onClick = {
                                                onSelectThemeMode(mode)
                                                isAppearanceMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)

                        // Reasoning Levels Selector Row (with multi-select DropdownMenu anchored to right side)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isReasoningMenuOpen = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ReasoningMeterIcon(
                                effort = ReasoningEffort.default,
                                tint = colors.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = "Reasoning levels",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${visibleReasoningEfforts.size} of ${ReasoningEffort.entries.size} enabled",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                                Icon(
                                    painter = painterResource(if (isReasoningMenuOpen) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
                                    contentDescription = "Select reasoning levels",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp),
                                )

                                DropdownMenu(
                                    expanded = isReasoningMenuOpen,
                                    onDismissRequest = { isReasoningMenuOpen = false },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = if (colors.isDark) Color(0xFF262628) else Color(0xFFFFFFFF),
                                    border = BorderStroke(1.dp, if (colors.isDark) Color(0xFF38383B) else Color(0xFFE5E7EB)),
                                    modifier = Modifier.width(220.dp),
                                ) {
                                    ReasoningEffort.entries.forEach { effort ->
                                        val isVisible = effort in visibleReasoningEfforts
                                        val canToggle = !isVisible || visibleReasoningEfforts.size > 1
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = effort.label,
                                                    color = if (canToggle || isVisible) colors.textPrimary else colors.textSecondary.copy(alpha = 0.5f),
                                                    fontSize = 15.sp,
                                                    fontWeight = if (isVisible) FontWeight.SemiBold else FontWeight.Normal,
                                                )
                                            },
                                            trailingIcon = {
                                                if (isVisible) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_check),
                                                        contentDescription = "Selected",
                                                        tint = colors.textPrimary,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            },
                                            onClick = {
                                                if (canToggle) {
                                                    onSetReasoningEffortVisibility(effort, !isVisible)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)

                        // Approved Apps Row
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
                                    text = "Approved apps",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (isFullAccess) "Full access enabled" else "$enabledCount of ${apps.size} enabled",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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

            // Integrations & System Section
            item {
                SettingsSectionHeader("Integrations")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.settingsCard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        // Desktop companion Row -> Opens dedicated screen
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                                .clickable { onOpenCompanion() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_laptop),
                                contentDescription = "Desktop companion",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = "Desktop companion",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (lanAddresses.isEmpty()) "Offline" else "Ready on Wi-Fi",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (lanAddresses.isEmpty()) "Offline" else "Ready",
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                Icon(
                                    painter = painterResource(R.drawable.ic_chevron_right),
                                    contentDescription = "Open Companion settings",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)

                        // Shizuku Service Row (with terminal/service icon)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                                .clickable(enabled = shizukuStatus.binderAvailable && !shizukuStatus.permissionGranted) {
                                    onRequestShizukuPermission()
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_terminal),
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
                                    text = "Shizuku service",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (shizukuStatus.permissionGranted) {
                                        "Shizuku is ready"
                                    } else if (shizukuStatus.binderAvailable) {
                                        "Permission required"
                                    } else {
                                        "Service unavailable"
                                    },
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = if (shizukuStatus.permissionGranted) "Active" else "Action needed",
                                color = if (shizukuStatus.permissionGranted) colors.textSecondary else colors.accentBlue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }

            // About Section
            item {
                SettingsSectionHeader("About")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.settingsCard,
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "0.1.0 • Android SDK 35",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompanionScreen(
    bridgeServer: DevBridgeServer,
    onBack: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    var lanAddresses by remember { mutableStateOf(bridgeServer.lanIpv4Addresses()) }
    var pairingCode by remember { mutableStateOf(bridgeServer.pairingCode) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textPrimary,
                ),
                title = { Text("Desktop Companion", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    Surface(
                        shape = CircleShape,
                        color = colors.composerBackground,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Back",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(18.dp),
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
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        ) {
            item {
                SettingsSectionHeader("Connection")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.settingsCard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_laptop),
                                contentDescription = "Desktop companion",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = "Wireless bridge",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (lanAddresses.isEmpty()) "Offline" else "Connected on local Wi-Fi",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = if (lanAddresses.isEmpty()) "Offline" else "Ready",
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }

                        HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)

                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Pairing code",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                )
                                TextButton(
                                    onClick = {
                                        pairingCode = bridgeServer.refreshPairingCode()
                                        lanAddresses = bridgeServer.lanIpv4Addresses()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text("Refresh", color = colors.accentBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            Text(
                                text = pairingCode.chunked(4).joinToString("-"),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            Text(
                                text = "Enter code in desktop companion",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                SettingsSectionFooter("The companion coordinates requests with desktop Codex over your local Wi-Fi.")
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
    var isFullAccess by remember { mutableStateOf(permissions.isFullAccessEnabled()) }
    var showFullAccessConfirmDialog by rememberSaveable { mutableStateOf(false) }
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
                                color = colors.composerBackground,
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
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = colors.background,
                            titleContentColor = colors.textPrimary,
                            actionIconContentColor = colors.textPrimary,
                            navigationIconContentColor = colors.textPrimary,
                        ),
                        title = { Text("Approved Apps", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                        navigationIcon = {
                            Surface(
                                shape = CircleShape,
                                color = colors.composerBackground,
                                border = BorderStroke(1.dp, colors.borderColor),
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { onBack() },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = "Back",
                                        tint = colors.textPrimary,
                                        modifier = Modifier.size(18.dp),
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
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { isSearchOpen = true },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_search),
                                        contentDescription = "Search",
                                        tint = colors.textPrimary,
                                        modifier = Modifier.size(18.dp),
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        ) {
            // Full Access Section
            item {
                SettingsSectionHeader("Global access")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.settingsCard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield),
                            contentDescription = "Full access",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 14.dp),
                        ) {
                            Text(
                                text = "Full access",
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Allow access to all installed apps",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = isFullAccess,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    showFullAccessConfirmDialog = true
                                } else {
                                    permissions.setFullAccessEnabled(false)
                                    isFullAccess = false
                                }
                            },
                            colors = assistantSwitchColors(colors),
                        )
                    }
                }
            }

            // Per-App List Section
            item {
                SettingsSectionHeader(if (isFullAccess) "Apps allowlist (Full access active)" else "Allowed apps")
                if (filteredApps.isEmpty()) {
                    Text(
                        text = if (searchQuery.isBlank()) "No launchable user apps found." else "No matching apps found.",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    )
                } else {
                    // Continuous joined card container for all apps with black dividers
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = colors.settingsCard,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            filteredApps.forEachIndexed { index, app ->
                                val enabled = if (isFullAccess) true else (app.packageName in enabledPackages)
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
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = app.packageName,
                                            fontSize = 12.sp,
                                            color = colors.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Switch(
                                        checked = enabled,
                                        enabled = !isFullAccess,
                                        onCheckedChange = { checked ->
                                            permissions.setEnabled(app.packageName, checked)
                                            enabledPackages = permissions.enabledPackages()
                                        },
                                        colors = assistantSwitchColors(colors),
                                    )
                                }
                                if (index < filteredApps.lastIndex) {
                                    HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFullAccessConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showFullAccessConfirmDialog = false },
            containerColor = colors.surfaceCard,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Enable Full Access?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "Full Access allows DHD to open, inspect, and operate any application installed on this device.\n\n" +
                        "This bypasses the per-app allowlist and lets DHD carry out tasks across all your apps.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFullAccessConfirmDialog = false
                        permissions.setFullAccessEnabled(true)
                        isFullAccess = true
                    },
                ) {
                    Text("Enable", color = colors.accentBlue, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullAccessConfirmDialog = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
        )
    }
}

@Composable
fun assistantSwitchColors(colors: AssistantColorScheme) = SwitchDefaults.colors(
    checkedThumbColor = if (colors.isDark) Color.Black else Color.White,
    checkedTrackColor = if (colors.isDark) Color.White else Color.Black,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = if (colors.isDark) Color(0xFF8E8E93) else Color(0xFF9CA3AF),
    uncheckedTrackColor = if (colors.isDark) Color(0xFF212124) else Color(0xFFE5E7EB),
    uncheckedBorderColor = Color.Transparent,
    disabledCheckedThumbColor = if (colors.isDark) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f),
    disabledCheckedTrackColor = if (colors.isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f),
    disabledUncheckedThumbColor = if (colors.isDark) Color(0xFF8E8E93).copy(alpha = 0.4f) else Color(0xFF9CA3AF).copy(alpha = 0.4f),
    disabledUncheckedTrackColor = if (colors.isDark) Color(0xFF212124).copy(alpha = 0.4f) else Color(0xFFE5E7EB).copy(alpha = 0.4f),
)

@Composable
private fun SettingsSectionHeader(title: String) {
    val colors = LocalAssistantColors.current
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.textSecondary,
        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsSectionFooter(text: String) {
    val colors = LocalAssistantColors.current
    Text(
        text = text,
        fontSize = 12.sp,
        color = colors.textSecondary,
        lineHeight = 16.sp,
        modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp),
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
