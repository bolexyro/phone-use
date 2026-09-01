package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.domain.userFacingActivityLabel
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.data.RunStatus
import com.phonecontrol.assistant.policy.PolicyContext
import com.phonecontrol.assistant.policy.PolicyDecision
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.shizuku.PhoneActionTransport
import com.phonecontrol.assistant.shizuku.TransportResult
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionState {
    data object Idle : SessionState

    data class Running(
        val sessionId: String,
        val request: String,
        val currentPurpose: String,
        val startedAtEpochMs: Long,
        val conversationId: String? = null,
        val reasoningEffort: String = ReasoningEffort.default.codexValue,
    ) : SessionState

    data class Paused(
        val sessionId: String,
        val request: String,
        val currentPurpose: String,
        val startedAtEpochMs: Long,
        val conversationId: String? = null,
        val reasoningEffort: String = ReasoningEffort.default.codexValue,
    ) : SessionState

    data class Stopped(
        val sessionId: String,
        val reason: String,
        val conversationId: String? = null,
    ) : SessionState

    data class Completed(
        val sessionId: String,
        val message: String,
        val conversationId: String? = null,
    ) : SessionState
}

sealed interface ActionExecutionResult {
    data object SessionNotRunning : ActionExecutionResult
    data class PolicyRejected(val message: String) : ActionExecutionResult
    data class TransportFinished(val result: TransportResult) : ActionExecutionResult
}

/**
 * A phone-originated request waiting for the desktop Codex companion to
 * accept it. The session id makes the handoff idempotent across polling and
 * prevents a stale desktop response from being applied to a newer session.
 */
data class PendingRequest(
    val sessionId: String,
    val request: String,
    val conversationId: String? = null,
    val codexThreadId: String? = null,
    val reasoningEffort: String = ReasoningEffort.default.codexValue,
)

/** A user instruction waiting to be appended to the active Codex turn. */
data class PendingSteer(
    val steerId: String,
    val sessionId: String,
    val text: String,
)

/**
 * Process-local session state shared by the Compose activity, foreground
 * service, and desktop Codex bridge. The phone owns the handoff and all typed
 * observation/action policy decisions.
 */
class SessionCoordinator(
    private val enabledPackagesProvider: () -> Set<String>,
    private val policyEngine: PolicyEngine,
    private val transport: PhoneActionTransport,
    private val conversationStore: ConversationStore? = null,
    /**
     * Phone actions stay queued until their local execution prerequisites are
     * ready. The default keeps the coordinator easy to exercise in unit tests.
     */
    private val phoneActionsReadyProvider: () -> Boolean = { true },
    private val fullAccessProvider: () -> Boolean = { false },
) {
    private val lock = Any()
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    private val _events = MutableStateFlow<List<ActivityEvent>>(emptyList())
    private var sessionJob: Job? = null
    private var claimedRequestSessionId: String? = null
    private val pendingSteers = mutableListOf<PendingSteer>()
    private val claimedSteers = mutableMapOf<String, PendingSteer>()

    val state: StateFlow<SessionState> = _state.asStateFlow()
    val events: StateFlow<List<ActivityEvent>> = _events.asStateFlow()

    fun start(
        request: String,
        conversationId: String? = null,
        reasoningEffort: String = ReasoningEffort.default.codexValue,
    ): Boolean = synchronized(lock) {
        if (request.isBlank() || _state.value.isActive) return false

        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val normalizedReasoningEffort = ReasoningEffort.fromCodexValue(reasoningEffort)?.codexValue
            ?: ReasoningEffort.default.codexValue
        val startedRun = conversationStore?.startRun(sessionId, request, conversationId)
        claimedRequestSessionId = null
        _state.value = SessionState.Running(
            sessionId = sessionId,
            request = request.trim(),
            currentPurpose = "Preparing request",
            startedAtEpochMs = now,
            conversationId = startedRun?.conversationId ?: conversationId,
            reasoningEffort = normalizedReasoningEffort,
        )
        sessionJob?.cancel()
        sessionJob = SupervisorJob()
        appendEvent(
            ActivityEventKind.SESSION_STARTED,
            "Request accepted. Waiting for the desktop Codex bridge.",
            sessionId = sessionId,
        )
        true
    }

    /** Return the active phone request until a desktop companion claims it. */
    fun pendingRequest(): PendingRequest? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        // Keep the request on the phone while Shizuku (the current phone
        // execution prerequisite) is unavailable. The companion must not
        // start an LLM turn that cannot safely reach the phone.
        if (!phoneActionsReadyProvider()) return@synchronized null
        if (claimedRequestSessionId == running.sessionId) return@synchronized null
        pendingRequestFor(running)
    }

    /** Queue a user instruction for the desktop companion's active Codex turn. */
    fun enqueueSteer(text: String): PendingSteer? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        val safeText = text.trim().take(MAX_STEER_CHARS).ifBlank { return@synchronized null }
        if (pendingSteers.size >= MAX_PENDING_STEERS) return@synchronized null

        val steer = PendingSteer(
            steerId = UUID.randomUUID().toString(),
            sessionId = running.sessionId,
            text = safeText,
        )
        pendingSteers += steer
        _state.value = running.copy(currentPurpose = "Steer queued")
        conversationStore?.setCurrentPurpose(running.sessionId, "Steer queued")
        conversationStore?.recordSteer(steer.steerId, running.sessionId, safeText)
        appendEvent(
            ActivityEventKind.SYSTEM,
            "Steer instruction queued for Codex.",
            sessionId = running.sessionId,
        )
        steer
    }

    /** Return the next unclaimed steer for the active phone session. */
    fun pendingSteer(expectedSessionId: String? = null): PendingSteer? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        if (expectedSessionId != null && expectedSessionId != running.sessionId) {
            return@synchronized null
        }
        pendingSteers.firstOrNull { it.sessionId == running.sessionId }
    }

    /** Atomically claim a steer so multiple desktop pollers cannot deliver it twice. */
    fun claimSteer(
        expectedSessionId: String,
        expectedSteerId: String,
    ): PendingSteer? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        if (expectedSessionId != running.sessionId) return@synchronized null
        val index = pendingSteers.indexOfFirst {
            it.sessionId == running.sessionId && it.steerId == expectedSteerId
        }
        if (index < 0) return@synchronized null
        val claimed = pendingSteers.removeAt(index)
        claimedSteers[claimed.steerId] = claimed
        claimed
    }

    /** Put a steer back at the front after a transient desktop delivery failure. */
    fun releaseSteer(expectedSessionId: String, steerId: String): Boolean = synchronized(lock) {
        val steer = claimedSteers[steerId] ?: return@synchronized false
        if (steer.sessionId != expectedSessionId) return@synchronized false
        val running = _state.value as? SessionState.Running
        if (running?.sessionId != steer.sessionId) return@synchronized false
        claimedSteers.remove(steerId)
        if (pendingSteers.none { it.steerId == steer.steerId }) pendingSteers.add(0, steer)
        true
    }

    /** Acknowledge that the active Codex client accepted a steer. */
    fun completeSteer(expectedSessionId: String, steerId: String): Boolean = synchronized(lock) {
        val steer = claimedSteers[steerId] ?: return@synchronized false
        if (steer.sessionId != expectedSessionId) return@synchronized false
        claimedSteers.remove(steerId) != null
    }

    /**
     * Atomically claim the current phone request. Pollers can pass the session
     * id they observed so a delayed claim cannot attach to a newer request.
     */
    fun claimRequest(expectedSessionId: String? = null): PendingRequest? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        if (expectedSessionId != null && expectedSessionId != running.sessionId) {
            return@synchronized null
        }
        if (!phoneActionsReadyProvider()) return@synchronized null
        if (claimedRequestSessionId == running.sessionId) return@synchronized null
        claimedRequestSessionId = running.sessionId
        _state.value = running.copy(currentPurpose = "Codex is planning")
        appendEvent(
            ActivityEventKind.SYSTEM,
            "Desktop Codex companion claimed the request.",
            sessionId = running.sessionId,
        )
        pendingRequestFor(running)
    }

    /** Release a claim after a desktop-side failure so the user can retry. */
    fun releaseRequest(sessionId: String): Boolean = synchronized(lock) {
        if (claimedRequestSessionId != sessionId) return@synchronized false
        val activeSessionId = _state.value.sessionIdOrNull
        if (activeSessionId != sessionId) {
            claimedRequestSessionId = null
            return@synchronized false
        }
        claimedRequestSessionId = null
        if (_state.value is SessionState.Running) {
            val running = _state.value as SessionState.Running
            _state.value = running.copy(currentPurpose = "Waiting for desktop Codex bridge")
        }
        appendEvent(
            ActivityEventKind.SYSTEM,
            "Desktop Codex companion released the request; waiting for retry.",
            sessionId = sessionId,
        )
        true
    }

    fun pause(): Boolean = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return false
        _state.value = SessionState.Paused(
            sessionId = running.sessionId,
            request = running.request,
            currentPurpose = running.currentPurpose,
            startedAtEpochMs = running.startedAtEpochMs,
            conversationId = running.conversationId,
            reasoningEffort = running.reasoningEffort,
        )
        conversationStore?.setRunStatus(running.sessionId, RunStatus.PAUSED)
        appendEvent(ActivityEventKind.SESSION_PAUSED, "Session paused.", running.sessionId)
        true
    }

    fun resume(): Boolean = synchronized(lock) {
        val paused = _state.value as? SessionState.Paused ?: return false
        _state.value = SessionState.Running(
            sessionId = paused.sessionId,
            request = paused.request,
            currentPurpose = paused.currentPurpose,
            startedAtEpochMs = paused.startedAtEpochMs,
            conversationId = paused.conversationId,
            reasoningEffort = paused.reasoningEffort,
        )
        conversationStore?.setRunStatus(paused.sessionId, RunStatus.RUNNING)
        appendEvent(ActivityEventKind.SESSION_RESUMED, "Session resumed.", paused.sessionId)
        true
    }

    fun togglePause(): Boolean = when (_state.value) {
        is SessionState.Running -> pause()
        is SessionState.Paused -> resume()
        else -> false
    }

    fun stop(reason: String = "Stopped by the user."): Boolean = synchronized(lock) {
        val sessionId = _state.value.sessionIdOrNull ?: return false
        sessionJob?.cancel()
        sessionJob = null
        claimedRequestSessionId = null
        clearSteers(sessionId)
        val conversationId = _state.value.conversationIdOrNull()
        _state.value = SessionState.Stopped(sessionId, reason, conversationId)
        conversationStore?.completeRun(sessionId, RunStatus.STOPPED)
        appendEvent(ActivityEventKind.SESSION_STOPPED, reason, sessionId)
        true
    }

    /**
     * End a run after a provider/bridge failure. This is intentionally distinct
     * from releaseRequest: a failed Codex turn must not be picked up and
     * replayed indefinitely by the polling companion.
     */
    fun fail(reason: String = "The desktop Codex turn failed."): Boolean = synchronized(lock) {
        val sessionId = _state.value.sessionIdOrNull ?: return false
        if (!_state.value.isActive) return false
        sessionJob?.cancel()
        sessionJob = null
        claimedRequestSessionId = null
        clearSteers(sessionId)
        val safeReason = reason.trim().take(MAX_AGENT_FEEDBACK_CHARS)
            .ifBlank { "The desktop Codex turn failed." }
        val conversationId = _state.value.conversationIdOrNull()
        _state.value = SessionState.Stopped(sessionId, "Failed: $safeReason", conversationId)
        conversationStore?.completeRun(
            sessionId,
            RunStatus.FAILED,
            assistantText = null,
        )
        appendEvent(ActivityEventKind.AGENT_MESSAGE, "DHD could not complete this request: $safeReason", sessionId)
        true
    }

    /**
     * Record a user-facing message from the desktop agent without exposing its
     * private reasoning stream. The message is kept in the local conversation
     * timeline and becomes the completed-session result.
     */
    fun complete(
        message: String = "Session completed.",
        agentFeedback: String? = null,
    ): Boolean = synchronized(lock) {
        val sessionId = _state.value.sessionIdOrNull ?: return false
        val feedback = agentFeedback
            ?.trim()
            ?.take(MAX_AGENT_FEEDBACK_CHARS)
            ?.ifBlank { null }
        val displayMessage = feedback ?: message.trim().take(MAX_TEXT_CHARS).ifBlank { "Session completed." }
        sessionJob = null
        claimedRequestSessionId = null
        val conversationId = _state.value.conversationIdOrNull()
        _state.value.sessionIdOrNull?.let(::clearSteers)
        _state.value = SessionState.Completed(sessionId, displayMessage, conversationId)
        // Feedback is emitted as an AGENT_MESSAGE below so the live timeline
        // and the durable timeline share one row. The fallback completion has
        // no separate event, so persist it directly here.
        conversationStore?.completeRun(
            sessionId,
            RunStatus.COMPLETED,
            assistantText = if (feedback == null) displayMessage else null,
        )
        if (feedback != null) {
            appendEvent(ActivityEventKind.AGENT_MESSAGE, feedback, sessionId)
        }
        appendEvent(
            ActivityEventKind.SESSION_COMPLETED,
            if (feedback != null) "Task completed." else displayMessage,
            sessionId,
        )
        true
    }

    /** Mark that the user should review the phone without launching an Activity. */
    fun requestAttention(reason: String): Boolean = synchronized(lock) {
        val current = _state.value
        val sessionId = current.sessionIdOrNull ?: return@synchronized false
        if (current !is SessionState.Running && current !is SessionState.Paused) {
            return@synchronized false
        }
        val message = reason.trim().take(MAX_TEXT_CHARS).ifBlank { "The phone assistant needs your attention." }
        setCurrentPurpose("Needs your attention")
        appendEvent(ActivityEventKind.ATTENTION_REQUIRED, message, sessionId)
        true
    }

    fun setCurrentPurpose(purpose: String): Boolean = synchronized(lock) {
        val displayPurpose = userFacingActivityLabel(actionType = null, purpose = purpose)
        val current = _state.value
        val updated = when (current) {
            is SessionState.Running -> current.copy(currentPurpose = displayPurpose)
            is SessionState.Paused -> current.copy(currentPurpose = displayPurpose)
            else -> return false
        }
        _state.value = updated
        current.sessionIdOrNull?.let { conversationStore?.setCurrentPurpose(it, displayPurpose) }
        true
    }

    fun bindCodexThread(conversationId: String, codexThreadId: String): Boolean {
        if (conversationStore == null || conversationId.isBlank() || codexThreadId.isBlank()) return false
        conversationStore.bindCodexThread(conversationId, codexThreadId)
        return true
    }

    /** Record a safe purpose-bearing operation such as a fresh screen observation. */
    fun recordPurpose(purpose: String, targetDescription: String? = null): Boolean = synchronized(lock) {
        val sessionId = _state.value.sessionIdOrNull ?: return@synchronized false
        val safePurpose = userFacingActivityLabel(actionType = null, purpose = purpose)
            .take(MAX_TEXT_CHARS)
            .ifBlank { return@synchronized false }
        val current = _state.value
        _state.value = when (current) {
            is SessionState.Running -> current.copy(currentPurpose = safePurpose)
            is SessionState.Paused -> current.copy(currentPurpose = safePurpose)
            else -> current
        }
        conversationStore?.setCurrentPurpose(sessionId, safePurpose)
        appendEvent(
            ActivityEventKind.SYSTEM,
            safePurpose,
            sessionId = sessionId,
            purpose = safePurpose,
            targetDescription = targetDescription?.trim()?.take(MAX_TEXT_CHARS),
        )
        true
    }

    /** Policy and transport integration point for the Codex bridge. */
    suspend fun executeAction(
        action: PhoneAction,
        observation: ObservationSnapshot?,
    ): ActionExecutionResult {
        val running = _state.value as? SessionState.Running
            ?: return ActionExecutionResult.SessionNotRunning
        val displayPurpose = userFacingActivityLabel(
            actionType = action.type,
            purpose = action.metadata.purpose,
            targetDescription = action.metadata.targetDescription,
        )
        setCurrentPurpose(displayPurpose)
        appendEvent(
            ActivityEventKind.ACTION_PROPOSED,
            // Keep the provider's safe explanation as the expandable detail;
            // the store derives the compact label from purpose + target.
            action.metadata.purpose,
            sessionId = running.sessionId,
            actionType = action.type,
            purpose = displayPurpose,
            observationId = action.metadata.observationId,
            targetDescription = action.metadata.targetDescription,
        )

        val decision = policyEngine.evaluate(
            action,
            PolicyContext(
                enabledPackages = enabledPackagesProvider(),
                foregroundPackage = observation?.packageName,
                currentObservationId = observation?.id,
                fullAccess = fullAccessProvider(),
            ),
        )
        when (decision) {
            PolicyDecision.Allowed -> Unit
            is PolicyDecision.Denied -> {
                appendEvent(
                    ActivityEventKind.ACTION_FAILED,
                    decision.message,
                    sessionId = running.sessionId,
                    actionType = action.type,
                    purpose = displayPurpose,
                    observationId = action.metadata.observationId,
                    targetDescription = action.metadata.targetDescription,
                )
                return ActionExecutionResult.PolicyRejected(decision.message)
            }
        }

        appendEvent(
            ActivityEventKind.ACTION_STARTED,
            "Executing ${action.type.name.lowercase().replace('_', ' ')}",
            sessionId = running.sessionId,
            actionType = action.type,
            purpose = displayPurpose,
            observationId = action.metadata.observationId,
            targetDescription = action.metadata.targetDescription,
        )
        val result = transport.execute(action, observation)
        val eventKind = if (result is TransportResult.Succeeded) {
            ActivityEventKind.ACTION_SUCCEEDED
        } else {
            ActivityEventKind.ACTION_FAILED
        }
        appendEvent(
            eventKind,
            transportMessage(result),
            sessionId = running.sessionId,
            actionType = action.type,
            purpose = displayPurpose,
            observationId = action.metadata.observationId,
            targetDescription = action.metadata.targetDescription,
        )
        return ActionExecutionResult.TransportFinished(result)
    }

    fun close() {
        sessionJob?.cancel()
        sessionJob = null
        synchronized(lock) {
            pendingSteers.clear()
            claimedSteers.clear()
        }
    }

    private fun clearSteers(sessionId: String) {
        pendingSteers.removeAll { it.sessionId == sessionId }
        claimedSteers.entries.removeIf { it.value.sessionId == sessionId }
    }

    private fun pendingRequestFor(running: SessionState.Running): PendingRequest = PendingRequest(
        sessionId = running.sessionId,
        request = running.request,
        conversationId = running.conversationId,
        codexThreadId = conversationStore?.codexThreadId(running.conversationId),
        reasoningEffort = running.reasoningEffort,
    )

    private fun appendEvent(
        kind: ActivityEventKind,
        message: String,
        sessionId: String? = _state.value.sessionIdOrNull,
        actionType: com.phonecontrol.assistant.domain.ActionType? = null,
        purpose: String? = null,
        observationId: String? = null,
        targetDescription: String? = null,
    ) {
        val event = ActivityEvent(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            timestampEpochMs = System.currentTimeMillis(),
            kind = kind,
            message = message,
            actionType = actionType,
            purpose = purpose,
            observationId = observationId,
            targetDescription = targetDescription,
        )
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
        conversationStore?.recordEvent(event)
    }

    private fun transportMessage(result: TransportResult): String = when (result) {
        is TransportResult.Rejected -> result.message
        is TransportResult.Unsupported -> result.message
        is TransportResult.Succeeded -> result.message
    }

    private companion object {
        const val MAX_EVENTS = 100
        const val MAX_TEXT_CHARS = 240
        const val MAX_AGENT_FEEDBACK_CHARS = 4_000
        const val MAX_STEER_CHARS = 4_000
        const val MAX_PENDING_STEERS = 8
    }
}

private val SessionState.isActive: Boolean
    get() = this is SessionState.Running || this is SessionState.Paused

private val SessionState.sessionIdOrNull: String?
    get() = when (this) {
        is SessionState.Idle -> null
        is SessionState.Running -> sessionId
        is SessionState.Paused -> sessionId
        is SessionState.Stopped -> sessionId
        is SessionState.Completed -> sessionId
    }

private fun SessionState.conversationIdOrNull(): String? = when (this) {
    is SessionState.Idle -> null
    is SessionState.Running -> conversationId
    is SessionState.Paused -> conversationId
    is SessionState.Stopped -> conversationId
    is SessionState.Completed -> conversationId
}
